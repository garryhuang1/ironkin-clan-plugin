package com.ironkinclan.manager;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.clan.ClanChannel;

/**
 * Reports on the nearby player group at a kill: who else is there, and how many of them are
 * members of the local player's in-game Clan, for features (like group boss submissions) that
 * need to know who was present and whether the clan made up the group.
 */
public class ClanMemberManager
{
	private final Client client;

	@Inject
	public ClanMemberManager(Client client)
	{
		this.client = client;
	}

	// Reads the top-level WorldView's players and the clan channel, so must be called on the
	// client thread.
	public GroupComposition getNearbyGroupComposition()
	{
		ClanChannel clanChannel = client.getClanChannel();
		Player localPlayer = client.getLocalPlayer();
		String localName = localPlayer == null ? null : localPlayer.getName();

		int totalPlayers = 0;
		int clanPlayers = 0;
		List<String> clanMembers = new ArrayList<>();

		for (Player player : client.getTopLevelWorldView().players())
		{
			String name = player.getName();
			if (name == null)
			{
				continue;
			}

			totalPlayers++;

			if (clanChannel == null || clanChannel.findMember(name) == null)
			{
				continue;
			}

			clanPlayers++;
			if (!name.equals(localName))
			{
				clanMembers.add(name);
			}
		}

		return new GroupComposition(totalPlayers, clanPlayers, clanMembers);
	}
}
