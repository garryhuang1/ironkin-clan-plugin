package com.ironkinclan.manager;

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

	// A strict majority (more than half) of the group must be in the clan. Solo kills never
	// qualify, even though a lone clan member would otherwise be a 100% majority of 1.
	public boolean isClanMajority()
	{
		return totalPlayers > 1 && clanPlayers * 2 > totalPlayers;
	}
}
