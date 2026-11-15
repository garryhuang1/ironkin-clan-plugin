package com.ironkinclan.manager;

import java.util.Arrays;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class InstancePartyTrackerTest
{
	private Client client;
	private WorldView worldView;
	private ClanChannel clanChannel;
	private InstancePartyTracker tracker;

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
		when(worldView.isInstance()).thenReturn(false);
		mockVisiblePlayers();
		inClan("LocalPlayer");

		tracker = new InstancePartyTracker(client);
	}

	@Test
	public void playersSeenInsideAreTheParty_onlyClanMembersReturned()
	{
		inClan("Clanmate");
		mockVisiblePlayers(player("LocalPlayer"), player("Clanmate"), player("Stranger"));

		enter();

		assertEquals(Collections.singletonList("Clanmate"), tracker.getClanMembers());
		assertEquals(2, tracker.getPartySize());
		assertTrue(tracker.isInInstance());
	}

	@Test
	public void playerWhoLeavesBeforeTheDrop_stillRemembered()
	{
		inClan("Clanmate");
		mockVisiblePlayers(player("LocalPlayer"), player("Clanmate"));
		enter();

		mockVisiblePlayers(player("LocalPlayer"));
		tracker.onGameTick();

		assertEquals(Collections.singletonList("Clanmate"), tracker.getClanMembers());
	}

	@Test
	public void playerWhoArrivesLater_isIncluded()
	{
		inClan("Late Mate");
		mockVisiblePlayers(player("LocalPlayer"));
		enter();

		tracker.onPlayerSpawned(player("Late Mate"));

		assertEquals(Collections.singletonList("Late Mate"), tracker.getClanMembers());
	}

	@Test
	public void clanmateWhoLeftTheChannel_stillCredited()
	{
		inClan("Clanmate");
		mockVisiblePlayers(player("Clanmate"));
		enter();

		when(clanChannel.findMember("Clanmate")).thenReturn(null);

		assertEquals(Collections.singletonList("Clanmate"), tracker.getClanMembers());
	}

	@Test
	public void leavingTheInstance_forgetsTheParty()
	{
		inClan("Clanmate");
		mockVisiblePlayers(player("Clanmate"));
		enter();

		when(worldView.isInstance()).thenReturn(false);
		tracker.onGameTick();

		assertFalse(tracker.isInInstance());
		assertTrue(tracker.getClanMembers().isEmpty());
		assertEquals(0, tracker.getPartySize());
	}

	@Test
	public void playersSeenOutsideAnInstanceAreIgnored()
	{
		inClan("Clanmate");
		mockVisiblePlayers(player("Clanmate"));

		tracker.onGameTick();
		tracker.onPlayerSpawned(player("Clanmate"));

		assertTrue(tracker.getClanMembers().isEmpty());
	}

	@Test
	public void enteringAgain_dropsThePreviousParty()
	{
		inClan("Clanmate");
		mockVisiblePlayers(player("Clanmate"));
		enter();

		when(worldView.isInstance()).thenReturn(false);
		tracker.onGameTick();

		inClan("New Mate");
		mockVisiblePlayers(player("New Mate"));
		enter();

		assertEquals(Collections.singletonList("New Mate"), tracker.getClanMembers());
	}

	@Test
	public void instanceRebuiltInPlace_dropsThePreviousParty()
	{
		inClan("Clanmate");
		when(worldView.getInstanceTemplateChunks()).thenReturn(new int[][][]{{{1}}});
		mockVisiblePlayers(player("Clanmate"));
		enter();

		when(worldView.getInstanceTemplateChunks()).thenReturn(new int[][][]{{{2}}});
		mockVisiblePlayers(player("New Mate"));
		inClan("New Mate");
		tracker.onGameTick();

		assertEquals(Collections.singletonList("New Mate"), tracker.getClanMembers());
	}

	@Test
	public void rebase_keepsOnlyWhoIsStillHere()
	{
		inClan("Clanmate");
		inClan("Still Here");
		mockVisiblePlayers(player("Clanmate"), player("Still Here"));
		enter();

		mockVisiblePlayers(player("Still Here"));
		tracker.rebaseToPlayersStillPresent();

		assertEquals(Collections.singletonList("Still Here"), tracker.getClanMembers());
	}

	private void enter()
	{
		when(worldView.isInstance()).thenReturn(true);
		tracker.onGameTick();
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
}
