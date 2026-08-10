package com.ironkinclan.manager;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.ironkinclan.api.IronkinClanApiClient;
import com.ironkinclan.config.IronkinClanConfig;
import com.ironkinclan.model.TrackedEventGroup;
import com.ironkinclan.model.TrackedItem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches and caches the tracked item lists for every active event returned by the server,
 * resolving item names on the client thread via {@link ItemManager}.
 */
@Slf4j
public class TrackedItemManager
{
	public interface Listener
	{
		void onTrackedItemsUpdated(List<TrackedEventGroup> events);
	}

	private final IronkinClanConfig config;
	private final IronkinClanApiClient apiClient;
	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ItemManager itemManager;
	private final ClientThread clientThread;

	@Inject
	public TrackedItemManager(IronkinClanConfig config, IronkinClanApiClient apiClient, OkHttpClient httpClient,
		Gson gson, ItemManager itemManager, ClientThread clientThread)
	{
		this.config = config;
		this.apiClient = apiClient;
		this.httpClient = httpClient;
		this.gson = gson;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
	}

	// eventId -> item IDs tracked by that event
	private final Map<String, Set<Integer>> eventItemIds = new ConcurrentHashMap<>();
	// itemId -> resolved name, union across all events
	private final Map<Integer, String> itemNames = new ConcurrentHashMap<>();
	private final AtomicBoolean itemListRequested = new AtomicBoolean();

	private Listener listener;
	private DiagnosticListener diagnosticListener;

	public void setListener(Listener listener)
	{
		this.listener = listener;
	}

	public void setDiagnosticListener(DiagnosticListener diagnosticListener)
	{
		this.diagnosticListener = diagnosticListener;
	}

	public boolean hasTrackedItems()
	{
		return !itemNames.isEmpty();
	}

	public String getItemName(int itemId)
	{
		return itemNames.get(itemId);
	}

	public List<String> getEventIdsForItem(int itemId)
	{
		List<String> matches = new ArrayList<>();
		for (Map.Entry<String, Set<Integer>> entry : eventItemIds.entrySet())
		{
			if (entry.getValue().contains(itemId))
			{
				matches.add(entry.getKey());
			}
		}
		return matches;
	}

	public void reset()
	{
		eventItemIds.clear();
		itemNames.clear();
		itemListRequested.set(false);
	}

	public void refresh()
	{
		if (!config.enableDropTracking())
		{
			return;
		}

		itemListRequested.set(false);
		fetch();
	}

	public void fetch()
	{
		if (config.serverUrl().isEmpty())
		{
			warn("Ironkin Clan server URL is not configured; skipping tracked item list fetch");
			return;
		}

		if (config.apiKey().isEmpty())
		{
			warn("Ironkin Clan API key is not configured; skipping tracked item list fetch");
			return;
		}

		if (!itemListRequested.compareAndSet(false, true))
		{
			return;
		}

		Request request = apiClient.newItemListRequest().build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				itemListRequested.set(false);
				warn("Failed to fetch Ironkin tracked item list: " + e.getMessage(), e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				ItemListResponse body;
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						itemListRequested.set(false);
						warn("Failed to fetch Ironkin tracked item list: HTTP " + r.code());
						return;
					}

					body = gson.fromJson(r.body().charStream(), ItemListResponse.class);
					if (body == null || body.events == null)
					{
						itemListRequested.set(false);
						warn("Ironkin tracked item list response was empty or malformed");
						return;
					}
				}
				catch (JsonSyntaxException e)
				{
					itemListRequested.set(false);
					warn("Failed to parse Ironkin tracked item list: " + e.getMessage(), e);
					return;
				}

				// The API only sends item IDs; resolve the canonical name from the client's own
				// item cache. ItemManager must be called on the client thread.
				clientThread.invoke(() ->
				{
					eventItemIds.clear();
					itemNames.clear();

					List<TrackedEventGroup> resolved = new ArrayList<>(body.events.size());
					for (EventEntry eventEntry : body.events)
					{
						List<TrackedItem> groupItems = new ArrayList<>(eventEntry.items.size());
						Set<Integer> ids = new HashSet<>();
						for (int itemId : eventEntry.items)
						{
							String name = itemManager.getItemComposition(itemId).getName();
							itemNames.put(itemId, name);
							ids.add(itemId);
							groupItems.add(new TrackedItem(itemId, name));
						}
						eventItemIds.put(eventEntry.eventId, ids);
						resolved.add(new TrackedEventGroup(eventEntry.eventId, groupItems, eventEntry.eventPassword));
					}

					if (listener != null)
					{
						listener.onTrackedItemsUpdated(resolved);
					}

					log.debug("Loaded {} tracked events with {} unique items", body.events.size(), itemNames.size());
				});
			}
		});
	}

	private void warn(String message)
	{
		log.warn(message);
		if (diagnosticListener != null)
		{
			diagnosticListener.onDiagnosticEvent(message, false);
		}
	}

	private void warn(String message, Throwable t)
	{
		log.warn(message, t);
		if (diagnosticListener != null)
		{
			diagnosticListener.onDiagnosticEvent(message, false);
		}
	}

	private static class ItemListResponse
	{
		List<EventEntry> events;
	}

	private static class EventEntry
	{
		String eventId;
		List<Integer> items;
		String eventPassword;
	}
}
