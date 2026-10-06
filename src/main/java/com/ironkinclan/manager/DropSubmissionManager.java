package com.ironkinclan.manager;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.ironkinclan.api.IronkinClanApiClient;
import com.ironkinclan.api.RequestSummary;
import com.ironkinclan.api.ResponsePreview;
import com.ironkinclan.api.RetryingCall;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Captures a screenshot of a tracked item drop and uploads it to the Ironkin server.
 */
@Slf4j
public class DropSubmissionManager
{
	private final IronkinClanApiClient apiClient;
	private final OkHttpClient httpClient;
	private final Gson gson;
	private final DrawManager drawManager;
	private final ScheduledExecutorService executor;

	@Inject
	public DropSubmissionManager(IronkinClanApiClient apiClient, OkHttpClient httpClient, Gson gson,
		DrawManager drawManager, ScheduledExecutorService executor)
	{
		this.apiClient = apiClient;
		this.httpClient = httpClient;
		this.gson = gson;
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

	// Separate from listener: receives background detail (encoding, retries) that belongs in the
	// panel log only, whereas listener carries the submission results that are also echoed to chat.
	public void setDiagnosticListener(DiagnosticListener diagnosticListener)
	{
		this.diagnosticListener = diagnosticListener;
	}

	// Unlike the two listeners above, what goes here leaves the machine, so it never gets the
	// username, the participants, the screenshot or the headers.
	public void setRemoteLogListener(RemoteLogListener remoteLogListener)
	{
		this.remoteLogListener = remoteLogListener;
	}

	public void reportDrop(String eventId, String username, int itemId, String itemName)
	{
		reportDrop(eventId, username, itemId, itemName, Collections.emptyList());
	}

	// participants is the set of other nearby clan members to credit alongside username - only
	// populated for group boss kills (see GroupBossRegistry), empty otherwise.
	public void reportDrop(String eventId, String username, int itemId, String itemName, List<String> participants)
	{
		long timestamp = System.currentTimeMillis();
		drawManager.requestNextFrameListener(image -> executor.execute(() -> uploadDrop(eventId, username, itemId, itemName, timestamp, participants, image)));
	}

	// Package-private (rather than private) so unit tests can exercise the encode/upload logic
	// directly without needing to drive the DrawManager frame-capture pipeline.
	void uploadDrop(String eventId, String username, int itemId, String itemName, long timestamp, List<String> participants, Image image)
	{
		String imageData;
		try
		{
			BufferedImage screenshot = ImageUtil.bufferedImageFromImage(image);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(screenshot, "png", baos);
			imageData = Base64.getEncoder().encodeToString(baos.toByteArray());
		}
		catch (IOException e)
		{
			log.warn("Failed to encode Ironkin drop screenshot for item {}", itemId, e);
			notifyListener("Failed to capture screenshot for " + itemName, false);
			notifyRemote(RemoteLogListener.Level.ERROR, "Failed to encode drop screenshot",
				Map.of("eventId", eventId, "itemId", itemId, "exception", e.toString()));
			return;
		}

		notifyDiagnostic("Encoded " + itemName + " drop screenshot for " + username + ": " + imageData.length() + " base64 chars", true);
		notifyListener("Captured screenshot for " + itemName + " drop", true);

		DropReport report = new DropReport(username, itemId, timestamp, imageData, participants);
		RequestBody body = RequestBody.create(IronkinClanApiClient.JSON, gson.toJson(report));
		// What gets written to the panel log if the upload fails: the same body, but with the
		// screenshot replaced by its size, since the base64 payload would swamp an exported log.
		String loggedBody = gson.toJson(new DropReport(username, itemId, timestamp,
			"[base64 PNG, " + imageData.length() + " chars]", participants));

		Request request = apiClient.newSubmissionRequest(eventId)
			.post(body)
			.build();

		// What goes to the logging service if the upload fails: the request without the screenshot
		// and without the player names (username, participants), which must not leave the machine.
		Map<String, Object> reportedRequest = RequestSummary.of(request,
			Map.of("itemid", itemId, "timestamp", timestamp, "participantCount", participants.size()));

		RetryingCall.enqueue(httpClient, executor, request, message -> notifyDiagnostic(message, false), new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed to upload Ironkin drop report for item {} to event {}", itemId, eventId, e);
				notifyListener("Failed to send " + itemName + " drop to " + eventId + ": " + e.getMessage(), false);
				notifyFailedRequest(request, loggedBody);
				notifyRemote(RemoteLogListener.Level.ERROR, "Failed to upload drop",
					Map.of("eventId", eventId, "itemId", itemId, "exception", e.toString(), "request", reportedRequest));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.warn("Ironkin drop report upload failed for item {} to event {}: HTTP {}", itemId, eventId, r.code());
						notifyListener("Failed to send " + itemName + " drop to " + eventId + " (HTTP " + r.code() + ")", false);
						notifyFailedRequest(request, loggedBody);
						String serverResponse = ResponsePreview.of(r);
						notifyDiagnostic("Server response (HTTP " + r.code() + "): " + serverResponse, false);
						notifyRemote(RemoteLogListener.Level.ERROR, "Drop upload returned an HTTP error",
							Map.of("eventId", eventId, "itemId", itemId, "httpStatus", r.code(),
								"request", reportedRequest, "response", serverResponse));
					}
					else
					{
						notifyListener("Sent " + itemName + " drop for " + username + " to " + eventId, true);
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

	private static class DropReport
	{
		@SerializedName("username")
		final String username;
		@SerializedName("itemid")
		final int itemId;
		@SerializedName("timestamp")
		final long timestamp;
		@SerializedName("imageData")
		final String imageData;
		@SerializedName("participants")
		final List<String> participants;

		DropReport(String username, int itemId, long timestamp, String imageData, List<String> participants)
		{
			this.username = username;
			this.itemId = itemId;
			this.timestamp = timestamp;
			this.imageData = imageData;
			this.participants = participants;
		}
	}
}
