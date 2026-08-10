package com.ironkinclan.manager;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.ironkinclan.api.IronkinClanApiClient;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
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

	public void setListener(DiagnosticListener listener)
	{
		this.listener = listener;
	}

	public void reportDrop(String eventId, String username, int itemId, String itemName)
	{
		long timestamp = System.currentTimeMillis();
		drawManager.requestNextFrameListener(image -> executor.execute(() -> uploadDrop(eventId, username, itemId, itemName, timestamp, image)));
	}

	// Package-private (rather than private) so unit tests can exercise the encode/upload logic
	// directly without needing to drive the DrawManager frame-capture pipeline.
	void uploadDrop(String eventId, String username, int itemId, String itemName, long timestamp, Image image)
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
			return;
		}

		log.debug("Encoded {} drop screenshot for {}: {} base64 chars", itemName, username, imageData.length());
		notifyListener("Captured screenshot for " + itemName + " drop", true);

		DropReport report = new DropReport(username, itemId, timestamp, imageData);
		RequestBody body = RequestBody.create(IronkinClanApiClient.JSON, gson.toJson(report));

		Request request = apiClient.newSubmissionRequest(eventId)
			.post(body)
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed to upload Ironkin drop report for item {} to event {}", itemId, eventId, e);
				notifyListener("Failed to send " + itemName + " drop to " + eventId + ": " + e.getMessage(), false);
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

		DropReport(String username, int itemId, long timestamp, String imageData)
		{
			this.username = username;
			this.itemId = itemId;
			this.timestamp = timestamp;
			this.imageData = imageData;
		}
	}
}
