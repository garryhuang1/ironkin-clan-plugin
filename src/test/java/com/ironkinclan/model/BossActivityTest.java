package com.ironkinclan.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BossActivityTest
{
	@Test
	public void requiredMessagePhrase_doomOfMokhaiotl_requiresFloors1To8Phrase()
	{
		assertEquals("level 1 - 8 duration", BossActivity.DOOM_OF_MOKHAIOTL.requiredMessagePhrase());
	}

	@Test
	public void requiredMessagePhrase_everyOtherActivity_isNull()
	{
		for (BossActivity activity : BossActivity.values())
		{
			if (activity == BossActivity.DOOM_OF_MOKHAIOTL)
			{
				continue;
			}

			assertNull(activity + " should not require an extra message phrase", activity.requiredMessagePhrase());
		}
	}

	@Test
	public void forNpcName_doomOfMokhaiotl_matchesAllThreePhaseNames()
	{
		assertEquals(BossActivity.DOOM_OF_MOKHAIOTL, BossActivity.forNpcName("Doom of Mokhaiotl"));
		assertEquals(BossActivity.DOOM_OF_MOKHAIOTL, BossActivity.forNpcName("Doom of Mokhaiotl (Shielded)"));
		assertEquals(BossActivity.DOOM_OF_MOKHAIOTL, BossActivity.forNpcName("Doom of Mokhaiotl (Burrowed)"));
	}

	@Test
	public void perLevelPersonalBestMessage_doesNotContainRequiredPhrase()
	{
		// Confirmed in-game: a single delve level's PB does not contain the floors-1-8 phrase.
		String perLevelMessage = "Delve level 5 duration: 0:50 (new personal best)";
		assertTrue(!perLevelMessage.toLowerCase().contains(BossActivity.DOOM_OF_MOKHAIOTL.requiredMessagePhrase()));
	}

	@Test
	public void fullRunPersonalBestMessage_containsRequiredPhrase()
	{
		// Confirmed in-game: the floors-1-8 aggregate PB message does contain the phrase.
		String fullRunMessage = "Delve level 1 - 8 duration: 9:04 (new personal best)";
		assertTrue(fullRunMessage.toLowerCase().contains(BossActivity.DOOM_OF_MOKHAIOTL.requiredMessagePhrase()));
	}
}
