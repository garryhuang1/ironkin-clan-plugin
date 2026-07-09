package com.ironkinclan;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.google.inject.Provides;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
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
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.loottracker.LootRecordType;
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

	private IronkinClanPanel panel;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientThread clientThread;

	private final Map<Integer, String> trackedItems = new ConcurrentHashMap<>();
	private final AtomicBoolean itemListRequested = new AtomicBoolean();
	private NavigationButton navButton;

	@Override
	protected void startUp()
	{
		panel = new IronkinClanPanel(itemManager);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Ironkin Clan")
			.icon(icon)
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		panel.setLogVisible(config.showUploadLog());
		panel.setOnActivate(this::refreshTrackedItems);

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

	// Uses the built-in Loot Tracker plugin's LootReceived broadcast rather than NpcLootReceived,
	// since Loot Tracker already funnels NPC kills, clue scroll rewards, raid/Barrows chests, and
	// most minigame rewards through one addLoot() call that posts this event. This only fires if
	// the user has the "Loot Tracker" plugin enabled (on by default, but can be disabled).
	//
	// PLAYER-type loot (PvP kills) is deliberately excluded and shouldn't be added: it would
	// report another player's gear to a third-party server, which is explicitly called out as a
	// rejected plugin behavior ("crowdsourcing data about other players... gear...").
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (!config.enableDropTracking() || trackedItems.isEmpty() || event.getType() == LootRecordType.PLAYER)
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

	private void refreshTrackedItems()
	{
		if (!config.enableDropTracking())
		{
			return;
		}

		itemListRequested.set(false);
		fetchTrackedItems();
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
		if (config.serverUrl().isEmpty())
		{
			log.warn("Ironkin Clan server URL is not configured; skipping tracked item list fetch");
			return;
		}

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
				TrackedItemListResponse body;
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						log.warn("Failed to fetch Ironkin tracked item list: HTTP {}", r.code());
						itemListRequested.set(false);
						return;
					}

					body = gson.fromJson(r.body().charStream(), TrackedItemListResponse.class);
					if (body == null || body.items == null)
					{
						log.warn("Ironkin tracked item list response was empty or malformed");
						itemListRequested.set(false);
						return;
					}
				}
				catch (JsonSyntaxException e)
				{
					log.warn("Failed to parse Ironkin tracked item list", e);
					itemListRequested.set(false);
					return;
				}

				// The API only sends item IDs; resolve the canonical name from the client's own
				// item cache. ItemManager must be called on the client thread.
				clientThread.invoke(() ->
				{
					List<TrackedItem> resolved = new ArrayList<>(body.items.size());
					for (BingoItem item : body.items)
					{
						String name = itemManager.getItemComposition(item.id).getName();
						trackedItems.put(item.id, name);
						resolved.add(new TrackedItem(item.id, name));
					}

					panel.setTrackedItems(resolved);

					log.debug("Loaded {} tracked items for bingo {}", trackedItems.size(), body.bingoId);
				});
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
						logUpload("Failed to send " + itemName + " drop", false);
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

		clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[Ironkin Clan] " + text, null));
	}

	@Provides
	IronkinClanConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(IronkinClanConfig.class);
	}

	private static class TrackedItemListResponse
	{
		String bingoId;
		List<BingoItem> items;
	}

	private static class BingoItem
	{
		int id;
	}

	static class TrackedItem
	{
		final int id;
		final String name;

		TrackedItem(int id, String name)
		{
			this.id = id;
			this.name = name;
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
