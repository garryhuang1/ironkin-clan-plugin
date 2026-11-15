package com.ironkinclan.manager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RaidPartyTrackerTest
{
	private static final String CHAMBERS = "Chambers of Xeric";
	private static final String THEATRE = "Theatre of Blood";
	private static final String TOMBS = "Tombs of Amascut";

	private Client client;
	private WorldView worldView;
	private ClanChannel clanChannel;
	private RaidPartyTracker tracker;
	private final List<String> diagnostics = new ArrayList<>();

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		worldView = mock(WorldView.class);
		clanChannel = mock(ClanChannel.class);
		Player localPlayer = player("LocalPlayer");
		when(client.getLocalPlayer()).thenReturn(localPlayer);
		when(client.getTopLevelWorldView()).thenReturn(worldView);
		when(client.getClanChannel()).thenReturn(clanChannel);
		when(client.getVarcStrValue(anyInt())).thenReturn("");
		when(worldView.isInstance()).thenReturn(true);
		mockVisiblePlayers();
		inClan("LocalPlayer");

		tracker = new RaidPartyTracker(client);
		tracker.setDiagnosticListener((text, success) -> diagnostics.add((success ? "ok " : "warn ") + text));
	}

	private static Player player(String name)
	{
		Player player = mock(Player.class);
		when(player.getName()).thenReturn(name);
		return player;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void mockVisiblePlayers(Player... players)
	{
		IndexedObjectSet playerSet = mock(IndexedObjectSet.class);
		when(playerSet.iterator()).thenReturn(Arrays.asList(players).iterator());
		when(worldView.players()).thenReturn(playerSet);
	}

	private void inClan(String name)
	{
		when(clanChannel.findMember(name)).thenReturn(mock(ClanChannelMember.class));
	}

	private void setName(int varc, String name)
	{
		when(client.getVarcStrValue(varc)).thenReturn(name);
		tracker.onVarClientStrChanged(varc);
	}

	// The line-up is withheld until the name slots have stopped changing, and until they differ
	// from the previous party or the give-up wait elapses. Pumping the give-up covers both.
	private String lastLineContaining(String fragment)
	{
		String found = "";
		for (String line : diagnostics)
		{
			if (line.contains(fragment))
			{
				found = line;
			}
		}
		return found;
	}

	private void finishLineUp()
	{
		for (int tick = 0; tick < RaidPartyTracker.LINEUP_GIVE_UP_TICKS; tick++)
		{
			tracker.onGameTick();
		}
	}

	@Test
	public void isRaidLoot_matchesTheThreeRaidChests()
	{
		assertTrue(RaidPartyTracker.isRaidLoot("Chambers of Xeric"));
		assertTrue(RaidPartyTracker.isRaidLoot("Theatre of Blood"));
		assertTrue(RaidPartyTracker.isRaidLoot("Tombs of Amascut"));
		assertFalse(RaidPartyTracker.isRaidLoot("Zulrah"));
		assertFalse(RaidPartyTracker.isRaidLoot(null));
	}

	@Test
	public void theatre_partyNamesReadOnEntry_onlyClanMembersReturned()
	{
		inClan("Clan Mate");
		when(client.getVarcStrValue(VarClientID.TOB_CLIENT_NAME0)).thenReturn("LocalPlayer");
		when(client.getVarcStrValue(VarClientID.TOB_CLIENT_NAME1)).thenReturn("Clan Mate");
		when(client.getVarcStrValue(VarClientID.TOB_CLIENT_NAME2)).thenReturn("Stranger");

		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 1);
		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 2);
		finishLineUp();

		assertEquals(Collections.singletonList("Clan Mate"), tracker.getClanMembers(THEATRE));
		assertEquals(2, tracker.getPartySize(THEATRE));
	}

	@Test
	public void theatre_namesLeftOverFromThePreviousParty_notReadOnEntry()
	{
		inClan("Old Mate");
		inClan("New Mate");
		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 2);
		setName(VarClientID.TOB_CLIENT_NAME1, "Old Mate");
		finishLineUp();
		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 1);

		// The entry varbit lands while the slot still names the previous teammate.
		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 2);
		setName(VarClientID.TOB_CLIENT_NAME1, "New Mate");
		finishLineUp();

		assertEquals(Collections.singletonList("New Mate"), tracker.getClanMembers(THEATRE));
	}

	@Test
	public void theatre_teammateWhoLeftBeforeTheChest_stillRemembered()
	{
		inClan("Clanmate");
		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 2);
		setName(VarClientID.TOB_CLIENT_NAME1, "Clanmate");
		finishLineUp();

		setName(VarClientID.TOB_CLIENT_NAME1, "");
		// Dying and respawning in the next room must not start a new party.
		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 3);
		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 2);

		assertEquals(Collections.singletonList("Clanmate"), tracker.getClanMembers(THEATRE));
	}

	@Test
	public void theatre_teammateWhoLeftTheClanChannel_stillCredited()
	{
		inClan("Clanmate");
		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 2);
		setName(VarClientID.TOB_CLIENT_NAME1, "Clanmate");
		finishLineUp();

		when(clanChannel.findMember("Clanmate")).thenReturn(null);

		assertEquals(Collections.singletonList("Clanmate"), tracker.getClanMembers(THEATRE));
	}

	@Test
	public void theatre_enteringAgain_dropsThePreviousLineUp()
	{
		inClan("Clanmate");
		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 2);
		setName(VarClientID.TOB_CLIENT_NAME1, "Clanmate");
		setName(VarClientID.TOB_CLIENT_NAME1, "");

		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 1);
		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 2);

		assertTrue(tracker.getClanMembers(THEATRE).isEmpty());
	}

	@Test
	public void theatre_leavingTheRaidAndTheParty_stillRememberedForTheLobbyChest()
	{
		inClan("Clanmate");
		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 2);
		setName(VarClientID.TOB_CLIENT_NAME1, "Clanmate");
		finishLineUp();

		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 1);
		setName(VarClientID.TOB_CLIENT_NAME1, "");
		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 0);

		assertEquals(Collections.singletonList("Clanmate"), tracker.getClanMembers(THEATRE));
	}

	@Test
	public void eachRaidKeepsItsOwnParty()
	{
		inClan("Theatre Mate");
		inClan("Chambers Mate");
		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 2);
		setName(VarClientID.TOB_CLIENT_NAME1, "Theatre Mate");
		finishLineUp();
		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 0);

		tracker.onVarbitChanged(VarbitID.RAIDS_CLIENT_INDUNGEON, 1);
		tracker.onPlayerSpawned(player("Chambers Mate"));

		assertEquals(Collections.singletonList("Theatre Mate"), tracker.getClanMembers(THEATRE));
		assertEquals(Collections.singletonList("Chambers Mate"), tracker.getClanMembers(CHAMBERS));
		assertTrue(tracker.getClanMembers(TOMBS).isEmpty());
		assertTrue(tracker.getClanMembers("Zulrah").isEmpty());
		assertEquals(0, tracker.getPartySize("Zulrah"));
	}

	@Test
	public void tombs_namesFromAllEightSlots()
	{
		inClan("Clanmate");
		tracker.onVarbitChanged(VarbitID.TOA_CLIENT_PARTYSTATUS, 2);
		setName(VarClientID.TOA_CLIENT_NAME7, "Clanmate");
		finishLineUp();

		assertEquals(Collections.singletonList("Clanmate"), tracker.getClanMembers(TOMBS));
	}

	@Test
	public void tombs_smallerParty_doesNotKeepThePreviousRaid()
	{
		inClan("oSteezz");
		inClan("Vibinglron");
		inClan("itTommy");

		tracker.onVarbitChanged(VarbitID.TOA_CLIENT_PARTYSTATUS, 2);
		setName(VarClientID.TOA_CLIENT_NAME1, "oSteezz");
		setName(VarClientID.TOA_CLIENT_NAME2, "Vibinglron");
		setName(VarClientID.TOA_CLIENT_NAME3, "itTommy");
		finishLineUp();

		tracker.onVarbitChanged(VarbitID.TOA_CLIENT_PARTYSTATUS, 0);

		// Entry still shows the previous party. A stale slot rewrite in that window, and the
		// slots that then blank, must not leave the previous raid on this one.
		tracker.onVarbitChanged(VarbitID.TOA_CLIENT_PARTYSTATUS, 2);
		tracker.onVarClientStrChanged(VarClientID.TOA_CLIENT_NAME2);
		setName(VarClientID.TOA_CLIENT_NAME2, "");
		setName(VarClientID.TOA_CLIENT_NAME3, "");
		finishLineUp();

		assertEquals(Collections.singletonList("oSteezz"), tracker.getClanMembers(TOMBS));
		String committed = lastLineContaining("party set to");
		assertTrue(committed.startsWith("ok "));
		assertTrue(committed.contains("party set to [oSteezz]"));
		assertTrue(committed.contains("slots changed from"));
		assertFalse(committed.contains("never changed"));
	}

	@Test
	public void tombs_unchangedSlots_warnThatThePreviousPartyMayHaveBeenKept()
	{
		inClan("Old Mate");
		when(client.getVarcStrValue(VarClientID.TOA_CLIENT_NAME1)).thenReturn("Old Mate");

		tracker.onVarbitChanged(VarbitID.TOA_CLIENT_PARTYSTATUS, 2);
		finishLineUp();

		assertEquals(Collections.singletonList("Old Mate"), tracker.getClanMembers(TOMBS));
		String committed = lastLineContaining("party set to");
		assertTrue(committed.startsWith("warn "));
		assertTrue(committed.contains("never changed"));
		assertTrue(committed.contains("Old Mate"));
	}

	@Test
	public void tombs_nameArrivingAfterTheLineUp_isIncluded()
	{
		inClan("Late Mate");
		tracker.onVarbitChanged(VarbitID.TOA_CLIENT_PARTYSTATUS, 2);
		finishLineUp();

		setName(VarClientID.TOA_CLIENT_NAME1, "Late Mate");

		assertEquals(Collections.singletonList("Late Mate"), tracker.getClanMembers(TOMBS));
	}

	@Test
	public void tombs_partyFormingOutside_doesNotJoinTheRememberedRaid()
	{
		inClan("Old Mate");
		inClan("Lobby Mate");
		tracker.onVarbitChanged(VarbitID.TOA_CLIENT_PARTYSTATUS, 2);
		setName(VarClientID.TOA_CLIENT_NAME1, "Old Mate");
		finishLineUp();

		tracker.onVarbitChanged(VarbitID.TOA_CLIENT_PARTYSTATUS, 1);
		setName(VarClientID.TOA_CLIENT_NAME2, "Lobby Mate");

		assertEquals(Collections.singletonList("Old Mate"), tracker.getClanMembers(TOMBS));
	}

	@Test
	public void chambers_playersSeenInsideAreTheParty()
	{
		inClan("Clanmate");
		inClan("Late Mate");
		Player clanmate = player("Clanmate");
		Player stranger = player("Stranger");
		mockVisiblePlayers(clanmate, stranger);

		tracker.onVarbitChanged(VarbitID.RAIDS_CLIENT_INDUNGEON, 1);
		tracker.onGameTick();
		tracker.onPlayerSpawned(player("Late Mate"));

		assertEquals(Arrays.asList("Clanmate", "Late Mate"), tracker.getClanMembers(CHAMBERS));
		assertEquals(3, tracker.getPartySize(CHAMBERS));
	}

	@Test
	public void chambers_playersOutsideTheEntranceWhenEntering_areNotTheParty()
	{
		inClan("Previous Mate");
		inClan("Clanmate");
		// Still standing outside: the varbit flips before the player is moved into the dungeon.
		when(worldView.isInstance()).thenReturn(false);
		mockVisiblePlayers(player("Previous Mate"));

		tracker.onVarbitChanged(VarbitID.RAIDS_CLIENT_INDUNGEON, 1);
		tracker.onPlayerSpawned(player("Previous Mate"));
		tracker.onGameTick();
		assertTrue(tracker.getClanMembers(CHAMBERS).isEmpty());

		when(worldView.isInstance()).thenReturn(true);
		mockVisiblePlayers(player("Clanmate"));
		tracker.onGameTick();

		assertEquals(Collections.singletonList("Clanmate"), tracker.getClanMembers(CHAMBERS));
	}

	@Test
	public void chambers_playersSeenOutsideTheDungeonAreIgnored()
	{
		inClan("Clanmate");

		tracker.onPlayerSpawned(player("Clanmate"));

		assertTrue(tracker.getClanMembers(CHAMBERS).isEmpty());
	}

	@Test
	public void chambers_newRaid_dropsThePreviousParty()
	{
		inClan("Clanmate");
		tracker.onVarbitChanged(VarbitID.RAIDS_CLIENT_INDUNGEON, 1);
		tracker.onPlayerSpawned(player("Clanmate"));

		tracker.onVarbitChanged(VarbitID.RAIDS_CLIENT_INDUNGEON, 0);
		assertEquals(Collections.singletonList("Clanmate"), tracker.getClanMembers(CHAMBERS));

		tracker.onVarbitChanged(VarbitID.RAIDS_CLIENT_INDUNGEON, 1);
		assertTrue(tracker.getClanMembers(CHAMBERS).isEmpty());
	}

	@Test
	public void sync_picksUpARaidAlreadyInProgress()
	{
		inClan("Clanmate");
		when(client.getVarbitValue(VarbitID.TOA_CLIENT_PARTYSTATUS)).thenReturn(2);
		when(client.getVarcStrValue(VarClientID.TOA_CLIENT_NAME1)).thenReturn("Clanmate");

		tracker.sync();

		assertEquals(Collections.singletonList("Clanmate"), tracker.getClanMembers(TOMBS));
	}
}
