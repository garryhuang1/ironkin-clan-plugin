package com.ironkinclan.manager;

import java.util.Arrays;
import java.util.Collections;
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
		mockVisiblePlayers();
		inClan("LocalPlayer");

		tracker = new RaidPartyTracker(client);
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

		assertEquals(Collections.singletonList("Clan Mate"), tracker.getClanMembers(THEATRE));
		assertEquals(2, tracker.getPartySize(THEATRE));
	}

	@Test
	public void theatre_teammateWhoLeftBeforeTheChest_stillRemembered()
	{
		inClan("Clanmate");
		tracker.onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, 2);
		setName(VarClientID.TOB_CLIENT_NAME1, "Clanmate");

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

		assertEquals(Collections.singletonList("Clanmate"), tracker.getClanMembers(TOMBS));
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
		tracker.onPlayerSpawned(player("Late Mate"));

		assertEquals(Arrays.asList("Clanmate", "Late Mate"), tracker.getClanMembers(CHAMBERS));
		assertEquals(3, tracker.getPartySize(CHAMBERS));
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
