package com.ironkinclan;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.google.inject.Provides;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "Ironkin Clan"
)
public class IronkinClanPlugin extends Plugin
{
	private static final String API_KEY_HEADER = "x-api-key";
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	@Inject
	private Client client;

	@Inject
	private IronkinClanConfig config;

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private Gson gson;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private IronkinClanPanel panel;

	private final Map<Integer, String> trackedItems = new ConcurrentHashMap<>();
	private final AtomicBoolean itemListRequested = new AtomicBoolean();
	private NavigationButton navButton;

	@Override
	protected void startUp()
	{
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Ironkin Clan")
			.icon(icon)
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		panel.setLogVisible(config.showUploadLog());

		if (config.enableDropTracking())
		{
			fetchTrackedItems();
		}
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		trackedItems.clear();
		itemListRequested.set(false);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!IronkinClanConfig.CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		switch (event.getKey())
		{
			case "serverUrl":
			case "bingoId":
			case "apiKey":
				trackedItems.clear();
				itemListRequested.set(false);
				panel.setTrackedItems(Collections.emptyList());
				if (config.enableDropTracking())
				{
					fetchTrackedItems();
				}
				break;
			case "enableDropTracking":
				if (config.enableDropTracking())
				{
					fetchTrackedItems();
				}
				break;
			case "showUploadLog":
				panel.setLogVisible(config.showUploadLog());
				break;
			default:
				break;
		}
	}

	// Known issue: only covers monster kill loot. Clue scroll rewards and raid chest loot
	// (CoX/ToB/ToA) aren't detected - see README "Known Issues".
	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (!config.enableDropTracking() || trackedItems.isEmpty())
		{
			return;
		}

		if (client.getLocalPlayer() == null)
		{
			return;
		}

		String username = client.getLocalPlayer().getName();
		for (ItemStack item : event.getItems())
		{
			String itemName = trackedItems.get(item.getId());
			if (itemName != null)
			{
				reportDrop(username, item.getId(), itemName);
			}
		}
	}

	private String baseUrl()
	{
		String url = config.serverUrl();
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	private String bingoUrl()
	{
		return baseUrl() + "/bingos/" + config.bingoId();
	}

	private void fetchTrackedItems()
	{
		String apiKey = config.apiKey();
		if (apiKey.isEmpty())
		{
			log.warn("Ironkin Clan API key is not configured; skipping tracked item list fetch");
			return;
		}

		if (config.bingoId().isEmpty())
		{
			log.warn("Ironkin Clan bingo ID is not configured; skipping tracked item list fetch");
			return;
		}

		if (!itemListRequested.compareAndSet(false, true))
		{
			return;
		}

		Request request = new Request.Builder()
			.url(bingoUrl())
			.header(API_KEY_HEADER, apiKey)
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed to fetch Ironkin tracked item list", e);
				itemListRequested.set(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						log.warn("Failed to fetch Ironkin tracked item list: HTTP {}", r.code());
						itemListRequested.set(false);
						return;
					}

					TrackedItemListResponse body = gson.fromJson(r.body().charStream(), TrackedItemListResponse.class);
					if (body == null || body.items == null)
					{
						log.warn("Ironkin tracked item list response was empty or malformed");
						itemListRequested.set(false);
						return;
					}

					for (TrackedItem item : body.items)
					{
						trackedItems.put(item.id, item.name);
					}

					panel.setTrackedItems(body.items);

					log.debug("Loaded {} tracked items for bingo {}", trackedItems.size(), body.bingoId);
				}
				catch (JsonSyntaxException e)
				{
					log.warn("Failed to parse Ironkin tracked item list", e);
					itemListRequested.set(false);
				}
			}
		});
	}

	private void reportDrop(String username, int itemId, String itemName)
	{
		long timestamp = System.currentTimeMillis();
		drawManager.requestNextFrameListener(image -> executor.execute(() -> uploadDrop(username, itemId, itemName, timestamp, image)));
	}

	private void uploadDrop(String username, int itemId, String itemName, long timestamp, Image image)
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
			logUpload("Failed to capture screenshot for " + itemName, false);
			return;
		}

		log.debug("Encoded {} drop screenshot for {}: {} base64 chars", itemName, username, imageData.length());

		DropReport report = new DropReport(username, itemId, timestamp, imageData);
		RequestBody body = RequestBody.create(JSON, gson.toJson(report));

		Request request = new Request.Builder()
			.url(bingoUrl())
			.header(API_KEY_HEADER, config.apiKey())
			.post(body)
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed to upload Ironkin drop report for item {}", itemId, e);
				logUpload("Failed to send " + itemName + " drop: " + e.getMessage(), false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.warn("Ironkin drop report upload failed for item {}: HTTP {}", itemId, r.code());
						logUpload("Failed to send " + itemName + " drop: HTTP " + r.code(), false);
					}
					else
					{
						logUpload("Sent " + itemName + " drop for " + username, true);
					}
				}
			}
		});
	}

	private void logUpload(String text, boolean success)
	{
		if (config.showUploadLog())
		{
			panel.addLogEntry(text, success);
		}
	}

	@Provides
	IronkinClanConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(IronkinClanConfig.class);
	}

	private static class TrackedItemListResponse
	{
		String bingoId;
		List<TrackedItem> items;
	}

	static class TrackedItem
	{
		int id;
		String name;
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
