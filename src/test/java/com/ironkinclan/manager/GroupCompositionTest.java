package com.ironkinclan.manager;

import java.util.Collections;
import org.junit.Test;

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
	public void hasClanBackup_noClanPlayersNearby_notEligible()
	{
		assertFalse(of(0, 2).hasClanBackup());
	}
}
