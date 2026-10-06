package com.ironkinclan.manager;

import com.ironkinclan.api.IronkinClanApiClient;
import com.ironkinclan.api.RequestSummary;
import com.ironkinclan.api.ResponsePreview;
import com.ironkinclan.api.RetryingCall;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Captures a screenshot of a new personal best and uploads it to the Ironkin Hall of Flame.
 */
public class PersonalBestManager
{
	private static final MediaType PNG = MediaType.get("image/png");

	private final IronkinClanApiClient apiClient;
	private final OkHttpClient httpClient;
	private final DrawManager drawManager;
	private final ScheduledExecutorService executor;

	@Inject
	public PersonalBestManager(IronkinClanApiClient apiClient, OkHttpClient httpClient,
		DrawManager drawManager, ScheduledExecutorService executor)
	{
		this.apiClient = apiClient;
		this.httpClient = httpClient;
		this.drawManager = drawManager;
		this.executor = executor;
	}

	private DiagnosticListener listener;
	private DiagnosticListener diagnosticListener;
	private RemoteLogListener remoteLogListener;

	public void setListener(DiagnosticListener listener)
	{
		this.listener = listener;
	}

	// Separate from listener: receives background detail (retries) that belongs in the panel log
	// only, whereas listener carries the submission results that are also echoed to chat.
	public void setDiagnosticListener(DiagnosticListener diagnosticListener)
	{
		this.diagnosticListener = diagnosticListener;
	}

	// Unlike the two listeners above, what goes here leaves the machine, so it never gets the
	// username, the screenshot or the headers.
	public void setRemoteLogListener(RemoteLogListener remoteLogListener)
	{
		this.remoteLogListener = remoteLogListener;
	}

	public void reportPersonalBest(String username, String bossName, String time)
	{
		drawManager.requestNextFrameListener(image -> executor.execute(() -> uploadPersonalBest(username, bossName, time, image)));
	}

	// Package-private (rather than private) so unit tests can exercise the encode/upload logic
	// directly without needing to drive the DrawManager frame-capture pipeline.
	void uploadPersonalBest(String username, String bossName, String time, Image image)
	{
		byte[] pngBytes;
		try
		{
			BufferedImage screenshot = ImageUtil.bufferedImageFromImage(image);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(screenshot, "png", baos);
			pngBytes = baos.toByteArray();
		}
		catch (IOException e)
		{
			notifyListener("Failed to capture screenshot for " + bossName + " personal best", false);
			notifyRemote(RemoteLogListener.Level.ERROR, "Failed to encode personal best screenshot",
				Map.of("boss", bossName, "exception", e.toString()));
			return;
		}

		MultipartBody body = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("player", username)
			.addFormDataPart("boss", bossName)
			.addFormDataPart("time", time)
			.addFormDataPart("proof", "personal-best.png", RequestBody.create(PNG, pngBytes))
			.build();

		// What gets written to the panel log if the upload fails: the form fields, with the
		// screenshot replaced by its size, since the image bytes would swamp an exported log.
		String loggedBody = "player=" + username + ", boss=" + bossName + ", time=" + time
			+ ", proof=[PNG, " + pngBytes.length + " bytes]";

		Request request = apiClient.newPersonalBestRequest()
			.post(body)
			.build();

		// What goes to the logging service if the upload fails: the form fields without the proof
		// image and without the player name, which must not leave the machine.
		Map<String, Object> reportedRequest = RequestSummary.of(request, Map.of("boss", bossName, "time", time));

		RetryingCall.enqueue(httpClient, executor, request, message -> notifyDiagnostic(message, false), new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				notifyListener("Failed to send " + bossName + " personal best (" + time + "): " + e.getMessage(), false);
				notifyFailedRequest(request, loggedBody);
				notifyRemote(RemoteLogListener.Level.ERROR, "Failed to upload personal best",
					Map.of("boss", bossName, "time", time, "exception", e.toString(), "request", reportedRequest));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						notifyListener("Failed to send " + bossName + " personal best (HTTP " + r.code() + ")", false);
						notifyFailedRequest(request, loggedBody);
						String serverResponse = ResponsePreview.of(r);
						notifyDiagnostic("Server response (HTTP " + r.code() + "): " + serverResponse, false);
						notifyRemote(RemoteLogListener.Level.ERROR, "Personal best upload returned an HTTP error",
							Map.of("boss", bossName, "time", time, "httpStatus", r.code(),
								"request", reportedRequest, "response", serverResponse));
					}
					else
					{
						notifyListener("Sent " + bossName + " personal best (" + time + ") for " + username, true);
					}
				}
			}
		});
	}

	private void notifyListener(String text, boolean success)
	{
		if (listener != null)
		{
			listener.onDiagnosticEvent(text, success);
		}
	}

	// Deliberately logs the method, URL and body only - never the headers, which carry the API key.
	private void notifyFailedRequest(Request request, String loggedBody)
	{
		notifyDiagnostic("Failed request was " + request.method() + " " + request.url() + " with body: " + loggedBody, false);
	}

	private void notifyDiagnostic(String text, boolean success)
	{
		if (diagnosticListener != null)
		{
			diagnosticListener.onDiagnosticEvent(text, success);
		}
	}

	private void notifyRemote(RemoteLogListener.Level level, String message, Map<String, Object> context)
	{
		if (remoteLogListener != null)
		{
			remoteLogListener.onRemoteLogEvent(level, message, context);
		}
	}
}
