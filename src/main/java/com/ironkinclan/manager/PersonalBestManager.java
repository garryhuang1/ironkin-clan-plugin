package com.ironkinclan.manager;

import com.ironkinclan.api.IronkinClanApiClient;
import com.ironkinclan.api.RetryingCall;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

	public void setListener(DiagnosticListener listener)
	{
		this.listener = listener;
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
			return;
		}

		MultipartBody body = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("player", username)
			.addFormDataPart("boss", bossName)
			.addFormDataPart("time", time)
			.addFormDataPart("proof", "personal-best.png", RequestBody.create(PNG, pngBytes))
			.build();

		Request request = apiClient.newPersonalBestRequest()
			.post(body)
			.build();

		RetryingCall.enqueue(httpClient, executor, request, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				notifyListener("Failed to send " + bossName + " personal best (" + time + "): " + e.getMessage(), false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						notifyListener("Failed to send " + bossName + " personal best (HTTP " + r.code() + ")", false);
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
}
