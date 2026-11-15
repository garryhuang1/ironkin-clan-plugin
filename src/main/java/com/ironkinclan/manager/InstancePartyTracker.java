package com.ironkinclan.manager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.clan.ClanChannel;
import net.runelite.client.util.Text;

/**
 * Remembers who was in the current boss instance with the local player. The instance is private
 * to the group, so every player seen inside it is part of that kill, including someone who leaves
 * before the drop appears.
 *
 * <p>A new instance, or leaving back to the shared world, drops the previous group. After a drop
 * is credited the party is cut down to whoever is still standing there, so the next kill in the
 * same instance does not keep people who already left.
 *
 * <p>Everything here runs on the client thread. Raids are not handled here; they have their own
 * party tracker.
 */
public class InstancePartyTracker
{
	private final Client client;

	private DiagnosticListener diagnosticListener;

	private final Set<String> names = new LinkedHashSet<>();
	private final Set<String> clanMembers = new LinkedHashSet<>();

	private boolean inInstance;
	private int instanceSignature;

	@Inject
	public InstancePartyTracker(Client client)
	{
		this.client = client;
	}

	public void setDiagnosticListener(DiagnosticListener diagnosticListener)
	{
		this.diagnosticListener = diagnosticListener;
	}

	public void sync()
	{
		onGameTick();
	}

	public void onPlayerSpawned(Player player)
	{
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null || !worldView.isInstance())
		{
			return;
		}

		boolean started = beginVisitIfNeeded(worldView);
		if (record(player.getName()) && !started)
		{
			notifyDiagnostic("Instance: added " + Text.sanitize(player.getName())
				+ " after the party was set; party is now " + new ArrayList<>(names), true);
		}
	}

	public void onGameTick()
	{
		WorldView worldView = client.getTopLevelWorldView();
		boolean inside = worldView != null && worldView.isInstance();
		if (!inside)
		{
			if (inInstance)
			{
				inInstance = false;
				clearParty();
				notifyDiagnostic("Instance: left, party cleared", true);
			}
			return;
		}

		boolean started = beginVisitIfNeeded(worldView);
		boolean anyNew = false;
		for (Player player : worldView.players())
		{
			anyNew |= record(player.getName());
		}

		if (started)
		{
			notifyDiagnostic("Instance: entered, players currently " + new ArrayList<>(names), true);
		}
		else if (anyNew)
		{
			notifyDiagnostic("Instance: players now " + new ArrayList<>(names), true);
		}
	}

	public boolean isInInstance()
	{
		return inInstance;
	}

	// Other players remembered in this instance, clan members or not.
	public int getPartySize()
	{
		if (!inInstance)
		{
			return 0;
		}

		String localName = localName();
		return names.size() - (localName != null && names.contains(localName) ? 1 : 0);
	}

	// Clan members seen in this instance, excluding the local player. Empty once the instance
	// has been left, so a later kill outside it cannot inherit the group.
	public List<String> getClanMembers()
	{
		if (!inInstance)
		{
			return Collections.emptyList();
		}

		ClanChannel clanChannel = client.getClanChannel();
		String localName = localName();

		List<String> result = new ArrayList<>();
		for (String name : names)
		{
			if (name.equals(localName))
			{
				continue;
			}

			if (clanMembers.contains(name) || (clanChannel != null && clanChannel.findMember(name) != null))
			{
				result.add(name);
			}
		}
		return result;
	}

	// Called after a drop from this instance has been credited. People who already left are
	// dropped, and whoever is still here is the start of the next kill.
	public void rebaseToPlayersStillPresent()
	{
		if (!inInstance)
		{
			return;
		}

		clearParty();
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView != null)
		{
			for (Player player : worldView.players())
			{
				record(player.getName());
			}
		}
		notifyDiagnostic("Instance: party reset to who is still here " + new ArrayList<>(names), true);
	}

	public void reset()
	{
		inInstance = false;
		instanceSignature = 0;
		clearParty();
	}

	private boolean beginVisitIfNeeded(WorldView worldView)
	{
		int signature = signature(worldView);
		if (inInstance && signature == instanceSignature)
		{
			return false;
		}

		clearParty();
		inInstance = true;
		instanceSignature = signature;
		return true;
	}

	private boolean record(String rawName)
	{
		if (rawName == null)
		{
			return false;
		}

		String name = Text.sanitize(rawName);
		if (name.isEmpty() || !names.add(name))
		{
			return false;
		}

		ClanChannel clanChannel = client.getClanChannel();
		if (clanChannel != null && clanChannel.findMember(name) != null)
		{
			clanMembers.add(name);
		}
		return true;
	}

	private void clearParty()
	{
		names.clear();
		clanMembers.clear();
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

	private static int signature(WorldView worldView)
	{
		int[][][] chunks = worldView.getInstanceTemplateChunks();
		return chunks == null ? 0 : Arrays.deepHashCode(chunks);
	}
}
