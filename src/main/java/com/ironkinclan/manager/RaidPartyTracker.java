package com.ironkinclan.manager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Text;

/**
 * Remembers who the local player raided with, for the whole length of the raid. A raid's loot
 * arrives when the reward chest is opened, by which point teammates have often already left, so
 * the players standing nearby at that moment say little about who was actually in the raid.
 *
 * Theatre of Blood and Tombs of Amascut publish the party's names to the client. Chambers of Xeric
 * does not (only the party size), but its dungeon is private to the party, so every player seen
 * inside it is a party member.
 *
 * Each raid keeps its own party, replaced only when that raid is entered again. Leaving the raid
 * or the party does not forget it, because unclaimed loot can still be collected from the chest
 * in the lobby afterwards.
 *
 * Everything here runs on the client thread.
 */
public class RaidPartyTracker
{
	private static final String CHAMBERS_OF_XERIC = "Chambers of Xeric";
	private static final String THEATRE_OF_BLOOD = "Theatre of Blood";
	private static final String TOMBS_OF_AMASCUT = "Tombs of Amascut";

	// TOB_CLIENT_PARTYSTATUS: 0 = no party, 1 = in a party outside, 2 = inside, 3 = dead inside.
	// TOA_CLIENT_PARTYSTATUS is assumed to follow the same scheme.
	private static final int STATUS_INSIDE = 2;

	private static final int[] TOB_NAME_VARCS = {
		VarClientID.TOB_CLIENT_NAME0,
		VarClientID.TOB_CLIENT_NAME1,
		VarClientID.TOB_CLIENT_NAME2,
		VarClientID.TOB_CLIENT_NAME3,
		VarClientID.TOB_CLIENT_NAME4
	};

	private static final int[] TOA_NAME_VARCS = {
		VarClientID.TOA_CLIENT_NAME0,
		VarClientID.TOA_CLIENT_NAME1,
		VarClientID.TOA_CLIENT_NAME2,
		VarClientID.TOA_CLIENT_NAME3,
		VarClientID.TOA_CLIENT_NAME4,
		VarClientID.TOA_CLIENT_NAME5,
		VarClientID.TOA_CLIENT_NAME6,
		VarClientID.TOA_CLIENT_NAME7
	};

	private final Client client;

	private final Party chambersParty = new Party();
	private final Party theatreParty = new Party();
	private final Party tombsParty = new Party();

	private boolean inChambers;
	private int tobStatus;
	private int toaStatus;

	@Inject
	public RaidPartyTracker(Client client)
	{
		this.client = client;
	}

	// Loot Tracker reports raid chests under these names.
	public static boolean isRaidLoot(String lootSource)
	{
		return CHAMBERS_OF_XERIC.equals(lootSource)
			|| THEATRE_OF_BLOOD.equals(lootSource)
			|| TOMBS_OF_AMASCUT.equals(lootSource);
	}

	// Picks up a raid already in progress, for when tracking starts part-way through one.
	public void sync()
	{
		onVarbitChanged(VarbitID.RAIDS_CLIENT_INDUNGEON, client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON));
		onVarbitChanged(VarbitID.TOB_CLIENT_PARTYSTATUS, client.getVarbitValue(VarbitID.TOB_CLIENT_PARTYSTATUS));
		onVarbitChanged(VarbitID.TOA_CLIENT_PARTYSTATUS, client.getVarbitValue(VarbitID.TOA_CLIENT_PARTYSTATUS));
	}

	public void onVarbitChanged(int varbitId, int value)
	{
		switch (varbitId)
		{
			case VarbitID.RAIDS_CLIENT_INDUNGEON:
				boolean inside = value > 0;
				if (inside && !inChambers)
				{
					chambersParty.clear();
					recordVisiblePlayers();
				}
				inChambers = inside;
				break;
			case VarbitID.TOB_CLIENT_PARTYSTATUS:
				tobStatus = onPartyStatusChanged(tobStatus, value, TOB_NAME_VARCS, theatreParty);
				break;
			case VarbitID.TOA_CLIENT_PARTYSTATUS:
				toaStatus = onPartyStatusChanged(toaStatus, value, TOA_NAME_VARCS, tombsParty);
				break;
			default:
				break;
		}
	}

	// A party member's name is blanked when they leave, so names are collected as they appear
	// rather than read once at the chest.
	public void onVarClientStrChanged(int index)
	{
		if (contains(TOB_NAME_VARCS, index))
		{
			record(theatreParty, client.getVarcStrValue(index));
		}
		else if (contains(TOA_NAME_VARCS, index))
		{
			record(tombsParty, client.getVarcStrValue(index));
		}
	}

	public void onPlayerSpawned(Player player)
	{
		if (inChambers)
		{
			record(chambersParty, player.getName());
		}
	}

	// Number of other players remembered in that raid's party, clan members or not.
	public int getPartySize(String lootSource)
	{
		Party party = partyFor(lootSource);
		if (party == null)
		{
			return 0;
		}

		String localName = localName();
		return party.names.size() - (localName != null && party.names.contains(localName) ? 1 : 0);
	}

	// The other members of that raid's party who are in the local player's clan channel, or were
	// when seen.
	public List<String> getClanMembers(String lootSource)
	{
		Party party = partyFor(lootSource);
		if (party == null)
		{
			return Collections.emptyList();
		}

		ClanChannel clanChannel = client.getClanChannel();
		String localName = localName();

		List<String> result = new ArrayList<>();
		for (String name : party.names)
		{
			if (name.equals(localName))
			{
				continue;
			}

			if (party.clanMembers.contains(name) || (clanChannel != null && clanChannel.findMember(name) != null))
			{
				result.add(name);
			}
		}
		return result;
	}

	public void reset()
	{
		chambersParty.clear();
		theatreParty.clear();
		tombsParty.clear();
		inChambers = false;
		tobStatus = 0;
		toaStatus = 0;
	}

	// Entering the raid starts a fresh party, so a member of an earlier line-up is not carried
	// into this one. Names set while the party was still forming are re-read here.
	private int onPartyStatusChanged(int previous, int value, int[] nameVarcs, Party party)
	{
		if (previous < STATUS_INSIDE && value >= STATUS_INSIDE)
		{
			party.clear();
			for (int varc : nameVarcs)
			{
				record(party, client.getVarcStrValue(varc));
			}
		}
		return value;
	}

	private void recordVisiblePlayers()
	{
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return;
		}

		for (Player player : worldView.players())
		{
			record(chambersParty, player.getName());
		}
	}

	private void record(Party party, String rawName)
	{
		if (rawName == null)
		{
			return;
		}

		// The party name varcs use non-breaking spaces, unlike Player.getName().
		String name = Text.sanitize(rawName);
		if (name.isEmpty())
		{
			return;
		}

		party.names.add(name);

		ClanChannel clanChannel = client.getClanChannel();
		if (clanChannel != null && clanChannel.findMember(name) != null)
		{
			party.clanMembers.add(name);
		}
	}

	private Party partyFor(String lootSource)
	{
		if (CHAMBERS_OF_XERIC.equals(lootSource))
		{
			return chambersParty;
		}
		if (THEATRE_OF_BLOOD.equals(lootSource))
		{
			return theatreParty;
		}
		if (TOMBS_OF_AMASCUT.equals(lootSource))
		{
			return tombsParty;
		}
		return null;
	}

	private String localName()
	{
		Player localPlayer = client.getLocalPlayer();
		return localPlayer == null ? null : localPlayer.getName();
	}

	private static boolean contains(int[] values, int value)
	{
		for (int candidate : values)
		{
			if (candidate == value)
			{
				return true;
			}
		}
		return false;
	}

	private static class Party
	{
		// Everyone seen in the party, the local player included.
		private final Set<String> names = new LinkedHashSet<>();
		// The subset that was in the clan channel when seen. Kept separately so a clanmate who
		// logs out after the raid, and so drops out of the channel, is still credited.
		private final Set<String> clanMembers = new LinkedHashSet<>();

		private void clear()
		{
			names.clear();
			clanMembers.clear();
		}
	}
}
