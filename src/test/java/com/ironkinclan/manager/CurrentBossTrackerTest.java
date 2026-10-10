package com.ironkinclan.manager;

import com.ironkinclan.model.BossActivity;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import org.junit.Before;
import org.junit.Test;

import static com.ironkinclan.manager.CurrentBossTracker.ENGAGEMENT_WINDOW_TICKS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CurrentBossTrackerTest
{
	private static final int TICK = 1000;

	private Actor localPlayer;
	private CurrentBossTracker tracker;

	@Before
	public void setUp()
	{
		localPlayer = mock(Actor.class);
		tracker = new CurrentBossTracker();
	}

	private static NPC npcNamed(String name)
	{
		NPC npc = mock(NPC.class);
		when(npc.getName()).thenReturn(name);
		return npc;
	}

	@Test
	public void onInteractingChanged_playerAttacksKnownBoss_tracksIt()
	{
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Vardorvis"), TICK);

		assertEquals(BossActivity.VARDORVIS, tracker.getCurrentBoss(TICK));
	}

	@Test
	public void onInteractingChanged_knownBossAttacksPlayer_tracksIt()
	{
		// OSRS sets "interacting" inconsistently depending on who initiates combat.
		tracker.onInteractingChanged(localPlayer, npcNamed("Vorkath"), localPlayer, TICK);

		assertEquals(BossActivity.VORKATH, tracker.getCurrentBoss(TICK));
	}

	@Test
	public void onInteractingChanged_unknownNpc_doesNotTrack()
	{
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Giant rat"), TICK);

		assertNull(tracker.getCurrentBoss(TICK));
	}

	@Test
	public void onInteractingChanged_neitherSideIsLocalPlayer_doesNotTrack()
	{
		Actor otherPlayer = mock(Actor.class);
		tracker.onInteractingChanged(localPlayer, otherPlayer, npcNamed("Vardorvis"), TICK);

		assertNull(tracker.getCurrentBoss(TICK));
	}

	@Test
	public void onInteractingChanged_multiNpcActivity_eitherNpcNameTracksIt()
	{
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Dawn"), TICK);
		assertEquals(BossActivity.GROTESQUE_GUARDIANS, tracker.getCurrentBoss(TICK));

		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Dusk"), TICK);
		assertEquals(BossActivity.GROTESQUE_GUARDIANS, tracker.getCurrentBoss(TICK));
	}

	@Test
	public void onInteractingChanged_switchingToUnknownNpc_keepsLastKnownBoss()
	{
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Zulrah"), TICK);
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Giant rat"), TICK + 1);

		assertEquals(BossActivity.ZULRAH, tracker.getCurrentBoss(TICK + 1));
	}

	@Test
	public void getCurrentBoss_withinWindow_returnsBoss()
	{
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Zulrah"), TICK);

		assertEquals(BossActivity.ZULRAH, tracker.getCurrentBoss(TICK + ENGAGEMENT_WINDOW_TICKS));
	}

	@Test
	public void getCurrentBoss_afterWindow_returnsNull()
	{
		// The reported bug: Leviathan fought earlier, then a Theatre of Blood completion PB
		// (whose NPCs aren't tracked) was attributed to Leviathan.
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("The Leviathan"), TICK);
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Verzik Vitur"), TICK + 2000);

		assertNull(tracker.getCurrentBoss(TICK + 2000));
	}

	@Test
	public void getCurrentBoss_tickCounterWentBackwards_returnsNull()
	{
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Zulrah"), TICK);

		assertNull(tracker.getCurrentBoss(TICK - 1));
	}

	@Test
	public void onGameTick_playerStillAttackingBoss_keepsItFresh()
	{
		// InteractingChanged fires once at the start of a long fight; the tick refresh has to
		// carry the boss through to the kill.
		NPC boss = npcNamed("Vardorvis");
		tracker.onInteractingChanged(localPlayer, localPlayer, boss, TICK);
		when(localPlayer.getInteracting()).thenReturn(boss);

		int killTick = TICK + 3 * ENGAGEMENT_WINDOW_TICKS;
		tracker.onGameTick(localPlayer, killTick);

		assertEquals(BossActivity.VARDORVIS, tracker.getCurrentBoss(killTick + 5));
	}

	@Test
	public void onGameTick_bossStillAttackingPlayer_keepsItFresh()
	{
		NPC boss = npcNamed("Vorkath");
		tracker.onInteractingChanged(localPlayer, boss, localPlayer, TICK);
		when(boss.getInteracting()).thenReturn(localPlayer);

		int killTick = TICK + 3 * ENGAGEMENT_WINDOW_TICKS;
		tracker.onGameTick(localPlayer, killTick);

		assertEquals(BossActivity.VORKATH, tracker.getCurrentBoss(killTick));
	}

	@Test
	public void onGameTick_noLongerEngaged_doesNotRefresh()
	{
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Zulrah"), TICK);

		int laterTick = TICK + 2 * ENGAGEMENT_WINDOW_TICKS;
		tracker.onGameTick(localPlayer, laterTick);

		assertNull(tracker.getCurrentBoss(laterTick));
	}

	@Test
	public void reset_clearsTrackedBoss()
	{
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Zulrah"), TICK);
		tracker.reset();

		assertNull(tracker.getCurrentBoss(TICK));
	}
}
