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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClanMemberManagerTest
{
	private Client client;
	private WorldView worldView;
	private ClanChannel clanChannel;
	private ClanMemberManager manager;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		worldView = mock(WorldView.class);
		clanChannel = mock(ClanChannel.class);
		when(client.getTopLevelWorldView()).thenReturn(worldView);
		when(client.getClanChannel()).thenReturn(clanChannel);

		manager = new ClanMemberManager(client);
	}

	private static Player player(String name)
	{
		Player player = mock(Player.class);
		when(player.getName()).thenReturn(name);
		return player;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void mockNearbyPlayers(Player... players)
	{
		IndexedObjectSet playerSet = mock(IndexedObjectSet.class);
		when(playerSet.iterator()).thenReturn(Arrays.asList(players).iterator());
		when(worldView.players()).thenReturn(playerSet);
	}

	@Test
	public void getNearbyGroupComposition_noClanChannel_noClanPlayersAndEmptyMembers()
	{
		Player someone = player("Someone");
		when(client.getClanChannel()).thenReturn(null);
		mockNearbyPlayers(someone);

		GroupComposition composition = manager.getNearbyGroupComposition();

		assertEquals(1, composition.totalPlayers);
		assertEquals(0, composition.clanPlayers);
		assertTrue(composition.clanMembers.isEmpty());
	}

	@Test
	public void getNearbyGroupComposition_countsClanPlayersAndExcludesLocalPlayerFromMembersList()
	{
		Player localPlayer = player("LocalPlayer");
		Player clanmate = player("Clanmate");
		Player stranger = player("Stranger");

		when(client.getLocalPlayer()).thenReturn(localPlayer);
		mockNearbyPlayers(localPlayer, clanmate, stranger);

		when(clanChannel.findMember(eq("LocalPlayer"))).thenReturn(mock(ClanChannelMember.class));
		when(clanChannel.findMember(eq("Clanmate"))).thenReturn(mock(ClanChannelMember.class));
		when(clanChannel.findMember(eq("Stranger"))).thenReturn(null);

		GroupComposition composition = manager.getNearbyGroupComposition();

		assertEquals(3, composition.totalPlayers);
		assertEquals(2, composition.clanPlayers);
		assertEquals(Collections.singletonList("Clanmate"), composition.clanMembers);
	}

	@Test
	public void getNearbyGroupComposition_noNearbyClanmates_zeroClanPlayers()
	{
		Player localPlayer = player("LocalPlayer");
		Player stranger = player("Stranger");
		when(client.getLocalPlayer()).thenReturn(localPlayer);
		mockNearbyPlayers(stranger);
		when(clanChannel.findMember(eq("Stranger"))).thenReturn(null);

		GroupComposition composition = manager.getNearbyGroupComposition();

		assertEquals(0, composition.clanPlayers);
		assertTrue(composition.clanMembers.isEmpty());
	}
}
