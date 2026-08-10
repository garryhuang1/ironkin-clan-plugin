package com.ironkinclan;

import com.google.inject.Provides;
import com.ironkinclan.config.IronkinClanConfig;
import com.ironkinclan.manager.DropSubmissionManager;
import com.ironkinclan.manager.TrackedItemManager;
import com.ironkinclan.model.TrackedEventGroup;
import com.ironkinclan.ui.EventPasswordOverlay;
import com.ironkinclan.ui.IronkinClanPanel;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.loottracker.LootRecordType;

@Slf4j
@PluginDescriptor(
	name = "Ironkin Clan"
)
public class IronkinClanPlugin extends Plugin
{
	// Renamed from "showUploadLog" when the panel log was expanded to also show diagnostic
	// warnings (e.g. failed item list fetches), not just drop upload results.
	private static final String OLD_SHOW_UPLOAD_LOG_KEY = "showUploadLog";

	@Inject
	private Client client;

	@Inject
	private IronkinClanConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private EventPasswordOverlay eventPasswordOverlay;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private TrackedItemManager trackedItemManager;

	@Inject
	private DropSubmissionManager dropSubmissionManager;

	private IronkinClanPanel panel;
	private NavigationButton navButton;
	private List<TrackedEventGroup> lastTrackedEvents = Collections.emptyList();

	@Override
	protected void startUp()
	{
		migrateShowUploadLogKey();
		removeObsoleteBingoIdKey();

		panel = new IronkinClanPanel(itemManager);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Ironkin Clan")
			.icon(icon)
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		panel.setLogVisible(config.showDebugLog());
		panel.setOnActivate(trackedItemManager::refresh);

		overlayManager.add(eventPasswordOverlay);

		trackedItemManager.setListener(this::onTrackedItemsUpdated);
		trackedItemManager.setDiagnosticListener(this::logDiagnostic);
		dropSubmissionManager.setListener(this::logUploadEvent);

		if (config.enableDropTracking())
		{
			trackedItemManager.fetch();
		}
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(eventPasswordOverlay);
		trackedItemManager.setListener(null);
		trackedItemManager.setDiagnosticListener(null);
		trackedItemManager.reset();
		dropSubmissionManager.setListener(null);
		lastTrackedEvents = Collections.emptyList();
	}

	private void onTrackedItemsUpdated(List<TrackedEventGroup> events)
	{
		lastTrackedEvents = events;
		panel.setTrackedItems(events);
		updateEventPasswordOverlay();
	}

	private void updateEventPasswordOverlay()
	{
		if (!config.showEventPasswords())
		{
			eventPasswordOverlay.setPasswordEvents(Collections.emptyList());
			return;
		}

		List<TrackedEventGroup> passwordEvents = new ArrayList<>();
		for (TrackedEventGroup event : lastTrackedEvents)
		{
			if (event.eventPassword != null && !event.eventPassword.isEmpty())
			{
				passwordEvents.add(event);
			}
		}

		eventPasswordOverlay.setPasswordEvents(passwordEvents);
	}

	private void migrateShowUploadLogKey()
	{
		String oldValue = configManager.getConfiguration(IronkinClanConfig.CONFIG_GROUP, OLD_SHOW_UPLOAD_LOG_KEY);
		if (oldValue != null)
		{
			configManager.setConfiguration(IronkinClanConfig.CONFIG_GROUP, "showDebugLog", oldValue);
			configManager.unsetConfiguration(IronkinClanConfig.CONFIG_GROUP, OLD_SHOW_UPLOAD_LOG_KEY);
		}
	}

	// The plugin no longer needs a user-configured event/bingo ID: the /events API now returns
	// every active event for the given API key, so this setting is obsolete.
	private void removeObsoleteBingoIdKey()
	{
		configManager.unsetConfiguration(IronkinClanConfig.CONFIG_GROUP, "bingoId");
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
			case "apiKey":
				trackedItemManager.reset();
				onTrackedItemsUpdated(Collections.emptyList());
				if (config.enableDropTracking())
				{
					trackedItemManager.fetch();
				}
				break;
			case "enableDropTracking":
				if (config.enableDropTracking())
				{
					trackedItemManager.fetch();
				}
				break;
			case "showDebugLog":
				panel.setLogVisible(config.showDebugLog());
				break;
			case "showEventPasswords":
				updateEventPasswordOverlay();
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
		if (!config.enableDropTracking())
		{
			return;
		}

		if (event.getType() == LootRecordType.PLAYER)
		{
			logDiagnostic("Ignoring PLAYER-type loot from " + event.getName() + " - PvP loot is intentionally not tracked", false);
			return;
		}

		if (!trackedItemManager.hasTrackedItems())
		{
			logDiagnostic("Ignoring loot from " + event.getName() + ": no tracked items loaded yet", false);
			return;
		}

		if (client.getLocalPlayer() == null)
		{
			logDiagnostic("Ignoring loot from " + event.getName() + ": local player is not available", false);
			return;
		}

		String username = client.getLocalPlayer().getName();
		log.debug("Processing loot event from {} ({}): {} item stack(s)", event.getName(), event.getType(), event.getItems().size());

		for (ItemStack item : event.getItems())
		{
			String itemName = trackedItemManager.getItemName(item.getId());
			if (itemName == null)
			{
				continue;
			}

			List<String> eventIds = trackedItemManager.getEventIdsForItem(item.getId());
			if (eventIds.isEmpty())
			{
				// Shouldn't happen - getItemName() only resolves names for items that came from
				// an event's list in the first place - but log it if the two ever disagree.
				logDiagnostic("Tracked item " + itemName + " (" + item.getId() + ") matched no events; skipping", false);
				continue;
			}

			log.debug("Matched tracked item {} ({}) x{} - reporting to event(s) {}", item.getId(), itemName, item.getQuantity(), eventIds);

			for (String eventId : eventIds)
			{
				dropSubmissionManager.reportDrop(eventId, username, item.getId(), itemName);
			}
		}
	}

	// Drop upload results are actionable per-event feedback, so they're echoed to game chat in
	// addition to the panel log. The panel always records the entry regardless of showDebugLog -
	// that setting only controls whether the log section is visible (see setLogVisible), so
	// toggling it on later reveals everything that happened while it was hidden instead of only
	// entries logged from that point forward.
	private void logUploadEvent(String text, boolean success)
	{
		panel.addLogEntry(text, success);

		clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[Ironkin Clan] " + text, null));
	}

	// Diagnostic warnings (e.g. a failed tracked item list fetch) are background/setup issues,
	// not per-event feedback, so they only go to the panel log rather than spamming game chat.
	private void logDiagnostic(String text, boolean success)
	{
		panel.addLogEntry(text, success);
	}

	@Provides
	IronkinClanConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(IronkinClanConfig.class);
	}
}
