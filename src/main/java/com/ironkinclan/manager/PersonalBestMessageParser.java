package com.ironkinclan.manager;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.client.util.Text;

/**
 * Recognizes RuneScape's "new personal best" game message (e.g. "Fight duration: 1:23.40
 * (new personal best)." or "Fight duration: 0:35 (new personal best).") and extracts the
 * completion time from it. This is the only client-side signal available for a new PB -
 * RuneLite can't read server-stored PB data directly.
 */
public final class PersonalBestMessageParser
{
	private static final String PB_MARKER = "new personal best";
	// The centisecond portion is optional - very short fights (e.g. "0:35") are reported
	// without one, confirmed against a real Shellbane Gryphon kill message in-game.
	private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,3}:\\d{2}(?:\\.\\d{1,2})?)");

	private PersonalBestMessageParser()
	{
	}

	/**
	 * @return the completion time (e.g. "1:23.40") if {@code rawMessage} announces a new
	 * personal best, or {@code null} otherwise.
	 */
	public static String parseTime(String rawMessage)
	{
		if (rawMessage == null)
		{
			return null;
		}

		String message = Text.removeTags(rawMessage);
		if (!message.toLowerCase().contains(PB_MARKER))
		{
			return null;
		}

		Matcher matcher = TIME_PATTERN.matcher(message);
		return matcher.find() ? matcher.group(1) : null;
	}
}
