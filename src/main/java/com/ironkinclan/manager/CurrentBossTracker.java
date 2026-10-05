package com.ironkinclan.manager;

import com.ironkinclan.model.BossActivity;
import net.runelite.api.Actor;
import net.runelite.api.NPC;

/**
 * Tracks which {@link BossActivity} the local player most recently engaged in combat with.
 * RuneScape's "new personal best" chat message rarely names the boss itself, so this is
 * used to attribute that message to the right activity when it arrives.
 */
public class CurrentBossTracker
{
	private BossActivity currentBoss;

	// OSRS sets "interacting" inconsistently depending on who initiates combat, so both
	// directions (player attacking the NPC, or the NPC attacking the player) are checked.
	public void onInteractingChanged(Actor localPlayer, Actor source, Actor target)
	{
		NPC npc;
		if (source == localPlayer && target instanceof NPC)
		{
			npc = (NPC) target;
		}
		else if (target == localPlayer && source instanceof NPC)
		{
			npc = (NPC) source;
		}
		else
		{
			return;
		}

		BossActivity activity = BossActivity.forNpcName(npc.getName());
		if (activity != null)
		{
			currentBoss = activity;
		}
	}

	public BossActivity getCurrentBoss()
	{
		return currentBoss;
	}

	public void reset()
	{
		currentBoss = null;
	}
}
