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
	public void isClanMajority_strictMajorityRatios_eligible()
	{
		assertTrue(of(2, 2).isClanMajority());
		assertTrue(of(2, 3).isClanMajority());
		assertTrue(of(3, 4).isClanMajority());
		assertTrue(of(3, 5).isClanMajority());
		assertTrue(of(4, 6).isClanMajority());
		assertTrue(of(4, 7).isClanMajority());
	}

	@Test
	public void isClanMajority_soloKill_notEligibleEvenThoughFullMajority()
	{
		assertFalse(of(1, 1).isClanMajority());
	}

	@Test
	public void isClanMajority_exactlyHalf_notEligible()
	{
		assertFalse(of(3, 6).isClanMajority());
		assertFalse(of(2, 4).isClanMajority());
	}

	@Test
	public void isClanMajority_minorityClan_notEligible()
	{
		assertFalse(of(1, 3).isClanMajority());
		assertFalse(of(0, 2).isClanMajority());
	}
}
