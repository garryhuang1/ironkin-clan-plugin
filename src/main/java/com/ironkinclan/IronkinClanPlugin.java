package com.ironkinclan;

import com.google.inject.Provides;
import com.ironkinclan.config.IronkinClanConfig;
import com.ironkinclan.manager.ClanMemberManager;
import com.ironkinclan.manager.CurrentBossTracker;
import com.ironkinclan.manager.DropSubmissionManager;
import com.ironkinclan.manager.EmberManager;
import com.ironkinclan.manager.GroupComposition;
import com.ironkinclan.manager.PersonalBestManager;
import com.ironkinclan.manager.PersonalBestMessageParser;
import com.ironkinclan.manager.RaidPartyTracker;
import com.ironkinclan.manager.RemoteLogListener;
import com.ironkinclan.manager.RemoteLogManager;
import com.ironkinclan.manager.TrackedItemManager;
import com.ironkinclan.model.BossActivity;
import com.ironkinclan.model.TrackedEventGroup;
import com.ironkinclan.ui.EventPasswordOverlay;
import com.ironkinclan.ui.IronkinClanPanel;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.VarClientStrChanged;
import net.runelite.api.events.VarbitChanged;
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
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

@PluginDescriptor(
	name = "Ironkin Clan"
)
public class IronkinClanPlugin extends Plugin
{
	// Renamed from "showUploadLog" when the panel log was expanded to also show diagnostic
	// warnings (e.g. failed item list fetches), not just drop upload results.
	private static final String OLD_SHOW_UPLOAD_LOG_KEY = "showUploadLog";

	// Server-side event ID for the group boss PvM entry-fee event. Drops reported to this event
	// also credit nearby clan members, since group bosses are commonly killed together.
	private static final String GROUP_BOSS_EVENT_ID = "pvm-entry";

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

	@Inject
	private ClanMemberManager clanMemberManager;

	@Inject
	private RaidPartyTracker raidPartyTracker;

	@Inject
	private EmberManager emberManager;

	@Inject
	private CurrentBossTracker currentBossTracker;

	@Inject
	private PersonalBestManager personalBestManager;

	@Inject
	private RemoteLogManager remoteLogManager;

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
		panel.setOnActivate(this::onPanelActivated);

		overlayManager.add(eventPasswordOverlay);

		trackedItemManager.setListener(this::onTrackedItemsUpdated);
		trackedItemManager.setDiagnosticListener(this::logDiagnostic);
		dropSubmissionManager.setListener(this::logUploadEvent);
		dropSubmissionManager.setDiagnosticListener(this::logDiagnostic);

		if (config.enableDropTracking())
		{
			trackedItemManager.fetch();
			syncRaidParty();
		}

		emberManager.setListener(this::onEmberBalanceUpdated);
		emberManager.setDiagnosticListener(this::logDiagnostic);
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			emberManager.start();
		}

		personalBestManager.setListener(this::logUploadEvent);
		personalBestManager.setDiagnosticListener(this::logDiagnostic);

		// Always scheduled: the manager itself checks the opt-in setting on every event and every
		// send, so toggling it needs no restart and nothing is queued or sent while it is off.
		trackedItemManager.setRemoteLogListener(remoteLogManager::log);
		dropSubmissionManager.setRemoteLogListener(remoteLogManager::log);
		emberManager.setRemoteLogListener(remoteLogManager::log);
		personalBestManager.setRemoteLogListener(remoteLogManager::log);
		remoteLogManager.start();
		remoteLogManager.log(RemoteLogListener.Level.INFO, "Plugin started", null);
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
		dropSubmissionManager.setDiagnosticListener(null);
		lastTrackedEvents = Collections.emptyList();
		clientThread.invoke(raidPartyTracker::reset);

		emberManager.stop();
		emberManager.setListener(null);
		emberManager.setDiagnosticListener(null);
		emberManager.reset();

		personalBestManager.setListener(null);
		personalBestManager.setDiagnosticListener(null);
		currentBossTracker.reset();

		trackedItemManager.setRemoteLogListener(null);
		dropSubmissionManager.setRemoteLogListener(null);
		emberManager.setRemoteLogListener(null);
		personalBestManager.setRemoteLogListener(null);
		remoteLogManager.stop();
	}

	private void onPanelActivated()
	{
		trackedItemManager.refresh();
		emberManager.fetch();
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
				emberManager.reset();
				emberManager.fetch();
				break;
			case "enableDropTracking":
				if (config.enableDropTracking())
				{
					trackedItemManager.fetch();
					syncRaidParty();
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

	// The startUp() fetch above only fires once and, if it fails (e.g. the server is briefly
	// unreachable while the client is still booting), nothing retries it unless the user happens
	// to open the panel. Retrying on every login is a cheap, panel-independent backstop:
	// fetch() is a no-op once the list has already loaded, so this only does real work when the
	// initial attempt never succeeded.
	//
	// Ember polling is also started/stopped here: it's only meaningful while actively logged in,
	// so it starts on login and stops once the client returns to the login screen rather than
	// running unattended in the background.
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		if (state == GameState.LOGGED_IN)
		{
			if (config.enableDropTracking())
			{
				trackedItemManager.fetch();
			}
			emberManager.start();
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			emberManager.stop();
		}
	}

	// The raid party is only followed while drop tracking is on, so a raid that was already under
	// way when it was switched on (or when the plugin started) has to be picked up by hand.
	private void syncRaidParty()
	{
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				raidPartyTracker.sync();
			}
		});
	}

	// The three subscribers below feed RaidPartyTracker, which remembers the raid party so a
	// pvm-entry drop from a raid chest can credit teammates who have already left.
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (config.enableDropTracking())
		{
			raidPartyTracker.onVarbitChanged(event.getVarbitId(), event.getValue());
		}
	}

	@Subscribe
	public void onVarClientStrChanged(VarClientStrChanged event)
	{
		if (config.enableDropTracking())
		{
			raidPartyTracker.onVarClientStrChanged(event.getIndex());
		}
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned event)
	{
		if (config.enableDropTracking())
		{
			raidPartyTracker.onPlayerSpawned(event.getPlayer());
		}
	}

	// Tracks which boss the local player is currently fighting, so a subsequent "new personal
	// best" chat message (which rarely names the boss itself) can be attributed correctly.
	// Checked first to avoid any NPC-name lookups while the feature is disabled.
	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (!config.enablePersonalBestTracking() || client.getLocalPlayer() == null)
		{
			return;
		}

		currentBossTracker.onInteractingChanged(client.getLocalPlayer(), event.getSource(), event.getTarget());
	}

	// RuneScape announces a new personal best via a game message rather than any readable
	// server-side state, so this is the only client-side signal available. If the message
	// doesn't match a tracked boss (see CurrentBossTracker), it's silently skipped rather
	// than submitting a guessed boss name the Hall of Flame site won't recognize.
	// Wrapped in a try/catch (unlike the other subscribers in this class) because this is one of
	// the two handlers that submits content to the Ironkin server - an unexpected exception here
	// shouldn't silently drop a personal best with no trace of what happened.
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		try
		{
			handleChatMessage(event);
		}
		catch (Exception e)
		{
			logDiagnostic("Unexpected error while processing a new personal best message: " + e, false);
			remoteLogManager.logException("Unexpected error while processing a personal best message", e, null);
		}
	}

	private void handleChatMessage(ChatMessage event)
	{
		if (!config.enablePersonalBestTracking() || event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		String time = PersonalBestMessageParser.parseTime(event.getMessage());
		if (time == null)
		{
			return;
		}

		BossActivity boss = currentBossTracker.getCurrentBoss();
		if (boss == null)
		{
			// This fires for plenty of content outside the Hall of Flame list too (raids, Nex,
			// Barrows, Wintertodt, etc.), so it's a diagnostic rather than a chat-echoed failure -
			// surfaced in the panel log (when enabled) so a user can tell why a PB didn't submit.
			// Tags are stripped so the panel shows clean text rather than raw <col=...> markup.
			logDiagnostic("Detected a new personal best (" + time + ") but couldn't identify the boss - not submitting: "
				+ Text.removeTags(event.getMessage()), false);
			return;
		}

		String requiredPhrase = boss.requiredMessagePhrase();
		if (requiredPhrase != null && !Text.removeTags(event.getMessage()).toLowerCase().contains(requiredPhrase))
		{
			// e.g. Doom of Mokhaiotl also reports a PB per individual delve level in the same
			// chat stream - only the message matching the required phrase is the one that
			// corresponds to this Hall of Flame category (see BossActivity.requiredMessagePhrase).
			logDiagnostic("Detected a new personal best (" + time + ") for " + boss.hallOfFlameName
				+ " but it wasn't the qualifying completion message (likely a sub-metric PB) - not submitting", false);
			return;
		}

		if (client.getLocalPlayer() == null)
		{
			logDiagnostic("Detected a new personal best for " + boss.hallOfFlameName
				+ " but the local player wasn't available - not submitting", false);
			return;
		}

		String username = client.getLocalPlayer().getName();
		// Logged before the screenshot is even captured (rather than only on eventual success/
		// failure) so a submission that never completes - e.g. the DrawManager callback never
		// firing - still leaves a trace of what was detected.
		logDiagnostic("Detected new personal best for " + boss.hallOfFlameName + " (" + time + ") - capturing screenshot", true);
		personalBestManager.reportPersonalBest(username, boss.hallOfFlameName, time);
	}

	// Uses the built-in Loot Tracker plugin's LootReceived broadcast rather than NpcLootReceived,
	// since Loot Tracker already funnels NPC kills, clue scroll rewards, raid/Barrows chests, and
	// most minigame rewards through one addLoot() call that posts this event. This only fires if
	// the user has the "Loot Tracker" plugin enabled (on by default, but can be disabled).
	//
	// PLAYER-type loot (PvP kills) is deliberately excluded and shouldn't be added: it would
	// report another player's gear to a third-party server, which is explicitly called out as a
	// rejected plugin behavior ("crowdsourcing data about other players... gear...").
	// Wrapped in a try/catch (unlike the other subscribers in this class) because this is one of
	// the two handlers that submits content to the Ironkin server - an unexpected exception here
	// shouldn't silently drop a loot report with no trace of what happened.
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		try
		{
			handleLootReceived(event);
		}
		catch (Exception e)
		{
			logDiagnostic("Unexpected error while processing loot from " + event.getName() + ": " + e, false);
			// The loot source is left out: for PvP loot it is another player's name.
			remoteLogManager.logException("Unexpected error while processing loot", e, null);
		}
	}

	private void handleLootReceived(LootReceived event)
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
		logDiagnostic("Processing loot event from " + event.getName() + " (" + event.getType() + "): "
			+ event.getItems().size() + " item stack(s)", true);

		// The nearby group is only relevant to the group boss event, so this is looked up at most
		// once per loot event rather than unconditionally on every drop.
		GroupComposition groupComposition = null;

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

			logDiagnostic("Matched tracked item " + itemName + " (" + item.getId() + ") x" + item.getQuantity()
				+ " - reporting to event(s) " + eventIds, true);

			for (String eventId : eventIds)
			{
				List<String> participants = Collections.emptyList();
				if (GROUP_BOSS_EVENT_ID.equals(eventId))
				{
					if (groupComposition == null)
					{
						groupComposition = clanMemberManager.getNearbyGroupComposition();
						// A raid chest is opened after the fight, when teammates may have left
						// already, so the party remembered over the raid counts as well.
						if (RaidPartyTracker.isRaidLoot(event.getName()))
						{
							List<String> raidClanMembers = raidPartyTracker.getClanMembers(event.getName());
							logDiagnostic("Raid party for " + event.getName() + ": " + raidClanMembers.size()
								+ " clan member(s) among " + raidPartyTracker.getPartySize(event.getName()) + " other party member(s)", true);
							groupComposition = groupComposition.withRaidClanMembers(raidClanMembers);
						}
					}

					if (!groupComposition.hasClanBackup())
					{
						logDiagnostic("Skipping " + itemName + " for " + eventId + ": no other clan member was nearby"
							+ " or in the raid party (" + groupComposition.clanPlayers + "/" + groupComposition.totalPlayers + ")", false);
						reportSkippedGroupBossDrop(event, item.getId(), groupComposition);
						continue;
					}

					participants = groupComposition.clanMembers;
				}

				dropSubmissionManager.reportDrop(eventId, username, item.getId(), itemName, participants);
			}
		}
	}

	// A skipped pvm-entry drop is the one a clan member is most likely to ask about, so it is
	// also sent to the logging service. Counts only: the names behind them stay on this machine.
	// The loot source is safe to send here because PLAYER-type loot never gets this far.
	private void reportSkippedGroupBossDrop(LootReceived event, int itemId, GroupComposition groupComposition)
	{
		boolean raidLoot = RaidPartyTracker.isRaidLoot(event.getName());

		Map<String, Object> context = new LinkedHashMap<>();
		context.put("eventId", GROUP_BOSS_EVENT_ID);
		context.put("itemId", itemId);
		context.put("lootSource", event.getName());
		context.put("lootType", String.valueOf(event.getType()));
		context.put("clanPlayers", groupComposition.clanPlayers);
		context.put("totalPlayers", groupComposition.totalPlayers);
		context.put("inClanChannel", client.getClanChannel() != null);
		context.put("raidLoot", raidLoot);
		if (raidLoot)
		{
			context.put("raidPartySize", raidPartyTracker.getPartySize(event.getName()));
		}

		remoteLogManager.log(RemoteLogListener.Level.WARN, "Skipped pvm-entry drop: no other clan member present", context);
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

	// Only announce increases in chat: embers are earned, not spent through this client, so a
	// decrease would just be a correction on the server side rather than something to celebrate.
	private void onEmberBalanceUpdated(int balance, Integer delta)
	{
		panel.setEmberBalance(balance);

		if (delta != null && delta > 0)
		{
			String message = "[Ironkin]: You have been awarded " + delta + " embers. Your total ember balance is now: " + balance + " embers.";
			clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null));
		}
	}

	@Provides
	IronkinClanConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(IronkinClanConfig.class);
	}
}
