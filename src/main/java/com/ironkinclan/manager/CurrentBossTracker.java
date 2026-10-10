package com.ironkinclan.manager;

import com.ironkinclan.model.BossActivity;
import net.runelite.api.Actor;
import net.runelite.api.NPC;

/**
 * Tracks which {@link BossActivity} the local player most recently engaged in combat with.
 * RuneScape's "new personal best" chat message rarely names the boss itself, so this is
 * used to attribute that message to the right activity when it arrives.
 * <p>
 * The tracked boss expires {@link #ENGAGEMENT_WINDOW_TICKS} after the player and the boss were
 * last seen engaged. Without that, a boss fought earlier in the session stayed "current" forever,
 * and a PB message from unsupported content (e.g. a Theatre of Blood completion after an earlier
 * Leviathan kill) was submitted as that boss.
 */
public class CurrentBossTracker
{
	// 60 seconds. The PB message arrives as the boss dies, so this only has to cover the death
	// animation and the odd tick of not attacking; it is far shorter than any unsupported
	// activity a player could finish after a supported boss.
	static final int ENGAGEMENT_WINDOW_TICKS = 100;

	private BossActivity currentBoss;
	private NPC currentBossNpc;
	private int lastEngagedTick;

	// OSRS sets "interacting" inconsistently depending on who initiates combat, so both
	// directions (player attacking the NPC, or the NPC attacking the player) are checked.
	public void onInteractingChanged(Actor localPlayer, Actor source, Actor target, int tick)
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
			currentBossNpc = npc;
			lastEngagedTick = tick;
		}
	}

	// InteractingChanged only fires when the target changes, so in a long fight the last event can
	// be minutes before the kill. Refreshing every tick while either side is still targeting the
	// other keeps the window anchored to the end of the fight rather than its start.
	public void onGameTick(Actor localPlayer, int tick)
	{
		if (currentBossNpc == null || localPlayer == null)
		{
			return;
		}

		if (localPlayer.getInteracting() == currentBossNpc || currentBossNpc.getInteracting() == localPlayer)
		{
			lastEngagedTick = tick;
		}
	}

	/**
	 * @return the boss the player was engaged with within the last
	 * {@link #ENGAGEMENT_WINDOW_TICKS} ticks of {@code tick}, or {@code null} if none.
	 */
	public BossActivity getCurrentBoss(int tick)
	{
		if (currentBoss == null)
		{
			return null;
		}

		int elapsed = tick - lastEngagedTick;
		// A negative gap means the tick counter restarted (e.g. a relog), which also means stale.
		return elapsed >= 0 && elapsed <= ENGAGEMENT_WINDOW_TICKS ? currentBoss : null;
	}

	public void reset()
	{
		currentBoss = null;
		currentBossNpc = null;
		lastEngagedTick = 0;
	}
}
