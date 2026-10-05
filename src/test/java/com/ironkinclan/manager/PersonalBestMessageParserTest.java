package com.ironkinclan.manager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PersonalBestMessageParserTest
{
	@Test
	public void parseTime_newPersonalBest_returnsTime()
	{
		assertEquals("1:23.40", PersonalBestMessageParser.parseTime("Fight duration: 1:23.40 (new personal best)."));
	}

	@Test
	public void parseTime_noCentiseconds_returnsTime()
	{
		// Real message from a Shellbane Gryphon kill - very short fights are reported as
		// plain M:SS with no centisecond portion.
		assertEquals("0:35", PersonalBestMessageParser.parseTime("Fight duration: 0:35 (new personal best)."));
	}

	@Test
	public void parseTime_colorTags_areStrippedBeforeMatching()
	{
		assertEquals("56:12.60", PersonalBestMessageParser.parseTime(
			"<col=ef1020>Duration: 56:12.60 (new personal best).</col>"));
	}

	@Test
	public void parseTime_caseInsensitiveMarker_returnsTime()
	{
		assertEquals("1:23.40", PersonalBestMessageParser.parseTime("Fight duration: 1:23.40 (New Personal Best)."));
	}

	@Test
	public void parseTime_noPersonalBestMarker_returnsNull()
	{
		assertNull(PersonalBestMessageParser.parseTime("Fight duration: 1:25.00. Personal best: 1:23.40."));
	}

	@Test
	public void parseTime_unrelatedMessage_returnsNull()
	{
		assertNull(PersonalBestMessageParser.parseTime("Your Zulrah kill count is: 50."));
	}

	@Test
	public void parseTime_nullMessage_returnsNull()
	{
		assertNull(PersonalBestMessageParser.parseTime(null));
	}
}
