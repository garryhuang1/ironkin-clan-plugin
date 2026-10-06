package com.ironkinclan.manager;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GroupCompositionTest
{
	private static GroupComposition of(int clanPlayers, int totalPlayers)
	{
		return new GroupComposition(totalPlayers, clanPlayers, Collections.emptyList());
	}

	@Test
	public void hasClanBackup_atLeastOneOtherClanMember_eligible()
	{
		assertTrue(of(2, 2).hasClanBackup());
		assertTrue(of(2, 3).hasClanBackup());
		assertTrue(of(2, 7).hasClanBackup());
		assertTrue(of(4, 7).hasClanBackup());
	}

	@Test
	public void hasClanBackup_soloKill_notEligible()
	{
		assertFalse(of(1, 1).hasClanBackup());
	}

	@Test
	public void withRaidClanMembers_teammateWhoLeft_makesSoloLooterEligible()
	{
		GroupComposition merged = of(1, 1).withRaidClanMembers(Collections.singletonList("Clanmate"));

		assertTrue(merged.hasClanBackup());
		assertEquals(2, merged.clanPlayers);
		assertEquals(2, merged.totalPlayers);
		assertEquals(Collections.singletonList("Clanmate"), merged.clanMembers);
	}

	@Test
	public void withRaidClanMembers_teammateStillNearby_notCountedTwice()
	{
		GroupComposition nearby = new GroupComposition(2, 2, Collections.singletonList("Clanmate"));

		GroupComposition merged = nearby.withRaidClanMembers(Arrays.asList("Clanmate", "Other Mate"));

		assertEquals(3, merged.clanPlayers);
		assertEquals(Arrays.asList("Clanmate", "Other Mate"), merged.clanMembers);
	}

	@Test
	public void withRaidClanMembers_noRaidClanMembers_unchanged()
	{
		assertFalse(of(1, 1).withRaidClanMembers(Collections.emptyList()).hasClanBackup());
	}

	@Test
	public void hasClanBackup_noClanPlayersNearby_notEligible()
	{
		assertFalse(of(0, 2).hasClanBackup());
	}
}
