package com.ironkinclan;

import com.google.inject.Provides;
import com.ironkinclan.config.IronkinClanConfig;
import com.ironkinclan.manager.DropSubmissionManager;
import com.ironkinclan.manager.TrackedItemManager;
import com.ironkinclan.ui.IronkinClanPanel;
import java.awt.image.BufferedImage;
import java.util.Collections;
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
	private ItemManager itemManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private TrackedItemManager trackedItemManager;

	@Inject
	private DropSubmissionManager dropSubmissionManager;

	private IronkinClanPanel panel;
	private NavigationButton navButton;

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

		trackedItemManager.setListener(panel::setTrackedItems);
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
		trackedItemManager.setListener(null);
		trackedItemManager.setDiagnosticListener(null);
		trackedItemManager.reset();
		dropSubmissionManager.setListener(null);
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
				panel.setTrackedItems(Collections.emptyList());
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
		if (!config.enableDropTracking() || !trackedItemManager.hasTrackedItems() || event.getType() == LootRecordType.PLAYER)
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
			String itemName = trackedItemManager.getItemName(item.getId());
			if (itemName == null)
			{
				continue;
			}

			for (String eventId : trackedItemManager.getEventIdsForItem(item.getId()))
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
