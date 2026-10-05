package com.ironkinclan.manager;

import com.ironkinclan.model.BossActivity;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CurrentBossTrackerTest
{
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
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Vardorvis"));

		assertEquals(BossActivity.VARDORVIS, tracker.getCurrentBoss());
	}

	@Test
	public void onInteractingChanged_knownBossAttacksPlayer_tracksIt()
	{
		// OSRS sets "interacting" inconsistently depending on who initiates combat.
		tracker.onInteractingChanged(localPlayer, npcNamed("Vorkath"), localPlayer);

		assertEquals(BossActivity.VORKATH, tracker.getCurrentBoss());
	}

	@Test
	public void onInteractingChanged_unknownNpc_doesNotTrack()
	{
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Giant rat"));

		assertNull(tracker.getCurrentBoss());
	}

	@Test
	public void onInteractingChanged_neitherSideIsLocalPlayer_doesNotTrack()
	{
		Actor otherPlayer = mock(Actor.class);
		tracker.onInteractingChanged(localPlayer, otherPlayer, npcNamed("Vardorvis"));

		assertNull(tracker.getCurrentBoss());
	}

	@Test
	public void onInteractingChanged_multiNpcActivity_eitherNpcNameTracksIt()
	{
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Dawn"));
		assertEquals(BossActivity.GROTESQUE_GUARDIANS, tracker.getCurrentBoss());

		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Dusk"));
		assertEquals(BossActivity.GROTESQUE_GUARDIANS, tracker.getCurrentBoss());
	}

	@Test
	public void onInteractingChanged_switchingToUnknownNpc_keepsLastKnownBoss()
	{
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Zulrah"));
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Giant rat"));

		assertEquals(BossActivity.ZULRAH, tracker.getCurrentBoss());
	}

	@Test
	public void reset_clearsTrackedBoss()
	{
		tracker.onInteractingChanged(localPlayer, localPlayer, npcNamed("Zulrah"));
		tracker.reset();

		assertNull(tracker.getCurrentBoss());
	}
}
