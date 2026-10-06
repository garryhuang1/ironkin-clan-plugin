package com.ironkinclan.manager;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of the nearby player group at the moment of a drop: how many players are present in
 * total, how many of them are in the local player's clan, and their names (excluding the local
 * player, who is implied to be one of clanPlayers if the local player is themselves in the clan).
 */
public class GroupComposition
{
	public final int totalPlayers;
	public final int clanPlayers;
	public final List<String> clanMembers;

	public GroupComposition(int totalPlayers, int clanPlayers, List<String> clanMembers)
	{
		this.totalPlayers = totalPlayers;
		this.clanPlayers = clanPlayers;
		this.clanMembers = clanMembers;
	}

	// Adds the clan members of the raid party who are not standing nearby any more (see
	// RaidPartyTracker), so they count towards eligibility and are credited as participants.
	public GroupComposition withRaidClanMembers(List<String> raidClanMembers)
	{
		List<String> merged = new ArrayList<>(clanMembers);
		for (String name : raidClanMembers)
		{
			if (!merged.contains(name))
			{
				merged.add(name);
			}
		}

		int added = merged.size() - clanMembers.size();
		return new GroupComposition(totalPlayers + added, clanPlayers + added, merged);
	}

	// At least one other clan member must be nearby. Solo kills never qualify - clanPlayers must
	// include the local player plus at least one more.
	public boolean hasClanBackup()
	{
		return clanPlayers > 1;
	}
}
