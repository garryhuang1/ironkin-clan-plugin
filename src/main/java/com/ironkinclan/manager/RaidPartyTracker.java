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

	// The name slots can still show the previous party for a few ticks after entry, and they
	// update one slot at a time. Wait this long, once the slots have stopped changing, before
	// accepting that they were already the party for this raid.
	static final int LINEUP_GIVE_UP_TICKS = 8;

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

	private DiagnosticListener diagnosticListener;

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

	public void setDiagnosticListener(DiagnosticListener diagnosticListener)
	{
		this.diagnosticListener = diagnosticListener;
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
		// Nothing is in flux here, unlike a real entry, so the slots showing now are this raid.
		commitLineUpIfWaiting(theatreParty, TOB_NAME_VARCS, THEATRE_OF_BLOOD);
		commitLineUpIfWaiting(tombsParty, TOA_NAME_VARCS, TOMBS_OF_AMASCUT);
		onGameTick();
	}

	public void onVarbitChanged(int varbitId, int value)
	{
		switch (varbitId)
		{
			case VarbitID.RAIDS_CLIENT_INDUNGEON:
				boolean inside = value > 0;
				if (inside && !inChambers)
				{
					chambersParty.startNew(Collections.emptyList());
					notifyDiagnostic(CHAMBERS_OF_XERIC + ": entered", true);
				}
				inChambers = inside;
				break;
			case VarbitID.TOB_CLIENT_PARTYSTATUS:
				tobStatus = onPartyStatusChanged(tobStatus, value, theatreParty, TOB_NAME_VARCS, THEATRE_OF_BLOOD);
				break;
			case VarbitID.TOA_CLIENT_PARTYSTATUS:
				toaStatus = onPartyStatusChanged(toaStatus, value, tombsParty, TOA_NAME_VARCS, TOMBS_OF_AMASCUT);
				break;
			default:
				break;
		}
	}

	// A party member's name is blanked when they leave, so names are collected as they appear
	// rather than read once at the chest. Until the new raid's line-up has settled, those updates
	// are ignored: the slots still hold the previous party, and a blank must not be treated as
	// someone who left this raid.
	public void onVarClientStrChanged(int index)
	{
		if (contains(TOB_NAME_VARCS, index))
		{
			if (tobStatus >= STATUS_INSIDE)
			{
				recordIfLineUpSettled(theatreParty, client.getVarcStrValue(index), THEATRE_OF_BLOOD);
			}
		}
		else if (contains(TOA_NAME_VARCS, index))
		{
			if (toaStatus >= STATUS_INSIDE)
			{
				recordIfLineUpSettled(tombsParty, client.getVarcStrValue(index), TOMBS_OF_AMASCUT);
			}
		}
	}

	public void onPlayerSpawned(Player player)
	{
		if (!isInsideChambers())
		{
			return;
		}

		// The first look at the dungeon happens on the following tick, but a player who spawns
		// before that, already inside the instance, is part of this raid. Logging them as a late
		// addition waits until that first look has been taken.
		if (chambersParty.awaitingLineUp)
		{
			record(chambersParty, player.getName());
			return;
		}

		recordIfLineUpSettled(chambersParty, player.getName(), CHAMBERS_OF_XERIC);
	}

	// Reads a new party's first line-up. The varbit that announces entry arrives while the name
	// slots can still hold the previous party, and those slots then update over the following
	// ticks. The line-up is the first set of names that differs from the slots at entry and has
	// held still for a tick. If the slots never change, they were already this raid's party.
	public void onGameTick()
	{
		if (chambersParty.awaitingLineUp && isInsideChambers())
		{
			chambersParty.awaitingLineUp = false;
			recordVisiblePlayers();
			notifyDiagnostic(CHAMBERS_OF_XERIC + ": party set to " + new ArrayList<>(chambersParty.names), true);
		}
		settleLineUp(theatreParty, TOB_NAME_VARCS, THEATRE_OF_BLOOD);
		settleLineUp(tombsParty, TOA_NAME_VARCS, TOMBS_OF_AMASCUT);
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
	// into this one. The slots at this moment are remembered only so the later read can tell
	// them apart from the party that actually enters.
	private int onPartyStatusChanged(int previous, int value, Party party, int[] nameVarcs, String raidName)
	{
		if (previous < STATUS_INSIDE && value >= STATUS_INSIDE)
		{
			List<String> atEntry = readNames(nameVarcs);
			party.startNew(atEntry);
			notifyDiagnostic(raidName + ": entered, name slots currently " + atEntry, true);
		}
		return value;
	}

	private void recordIfLineUpSettled(Party party, String rawName, String raidName)
	{
		if (party.awaitingLineUp)
		{
			return;
		}

		String name = sanitize(rawName);
		boolean alreadyPresent = party.names.contains(name);
		record(party, rawName);
		if (!name.isEmpty() && !alreadyPresent && party.names.contains(name))
		{
			notifyDiagnostic(raidName + ": added " + name + " after the party was set; party is now " + new ArrayList<>(party.names), true);
		}
	}

	private void settleLineUp(Party party, int[] nameVarcs, String raidName)
	{
		if (!party.awaitingLineUp)
		{
			return;
		}

		List<String> current = readNames(nameVarcs);
		party.lineupTicks++;
		List<String> previous = party.namesLastTick != null ? party.namesLastTick : party.namesAtEntry;
		if (!current.equals(previous))
		{
			notifyDiagnostic(raidName + ": name slots changed to " + current, true);
		}

		boolean changedFromEntry = !current.equals(party.namesAtEntry);
		boolean stable = party.namesLastTick != null && current.equals(party.namesLastTick);
		party.namesLastTick = current;

		boolean arrived = changedFromEntry && stable;
		boolean unchangedAndWaited = !changedFromEntry && stable && party.lineupTicks >= LINEUP_GIVE_UP_TICKS;
		if (arrived)
		{
			commitLineUp(party, current);
			notifyDiagnostic(raidName + ": party set to " + current + " (slots changed from " + party.namesAtEntry + ")", true);
		}
		else if (unchangedAndWaited)
		{
			commitLineUp(party, current);
			notifyDiagnostic(raidName + ": party set to " + current
				+ " (name slots never changed after entry; this may still be the previous party)", false);
		}
	}

	private void commitLineUpIfWaiting(Party party, int[] nameVarcs, String raidName)
	{
		if (!party.awaitingLineUp)
		{
			return;
		}

		List<String> current = readNames(nameVarcs);
		commitLineUp(party, current);
		notifyDiagnostic(raidName + ": picked up a raid already in progress; party is " + current, true);
	}

	private void commitLineUp(Party party, List<String> current)
	{
		party.awaitingLineUp = false;
		party.names.clear();
		party.clanMembers.clear();
		for (String name : current)
		{
			record(party, name);
		}
	}

	private List<String> readNames(int[] nameVarcs)
	{
		List<String> current = new ArrayList<>();
		for (int varc : nameVarcs)
		{
			String name = sanitize(client.getVarcStrValue(varc));
			if (!name.isEmpty())
			{
				current.add(name);
			}
		}
		return current;
	}

	// The dungeon is an instance and the area outside its entrance is not, which tells the two
	// apart on the ticks where the varbit and the player's actual position disagree.
	private boolean isInsideChambers()
	{
		if (!inChambers)
		{
			return false;
		}

		WorldView worldView = client.getTopLevelWorldView();
		return worldView != null && worldView.isInstance();
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
		String name = sanitize(rawName);
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

	private void notifyDiagnostic(String text, boolean success)
	{
		if (diagnosticListener != null)
		{
			diagnosticListener.onDiagnosticEvent(text, success);
		}
	}

	private static String sanitize(String rawName)
	{
		if (rawName == null)
		{
			return "";
		}

		// The party name varcs use non-breaking spaces, unlike Player.getName().
		return Text.sanitize(rawName);
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
		// Set on entering the raid, until the line-up has settled.
		private boolean awaitingLineUp;
		private int lineupTicks;
		// Names in the slots at the moment entry was announced. Compared against later reads so
		// the previous party is not committed as this one.
		private List<String> namesAtEntry = Collections.emptyList();
		// The last tick's slots, or null until a tick has been observed. The first observation
		// is never "stable", so a blank frame at entry is not taken as the line-up.
		private List<String> namesLastTick;

		private void clear()
		{
			names.clear();
			clanMembers.clear();
			awaitingLineUp = false;
			lineupTicks = 0;
			namesAtEntry = Collections.emptyList();
			namesLastTick = null;
		}

		private void startNew(List<String> namesAtEntry)
		{
			clear();
			awaitingLineUp = true;
			this.namesAtEntry = namesAtEntry;
		}
	}
}
