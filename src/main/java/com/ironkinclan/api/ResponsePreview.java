package com.ironkinclan.api;

import java.io.IOException;
import okhttp3.Response;

/**
 * Produces a short, single-line excerpt of a response body for the panel log, so an HTTP error
 * can be reported together with whatever reason the server gave for it.
 */
public final class ResponsePreview
{
	// Enough for a JSON error message; an HTML error page or stack trace gets cut off rather than
	// flooding the log.
	static final int MAX_LENGTH = 500;

	private ResponsePreview()
	{
	}

	// Uses peekBody() so the body is left unconsumed for the caller, and never reads more than a
	// bounded amount regardless of how large the response is.
	public static String of(Response response)
	{
		if (response.body() == null)
		{
			return "(no body)";
		}

		String text;
		try
		{
			// A few bytes past the limit, so a body that exceeds it can be told apart from one that
			// fits exactly (multi-byte characters make the byte and character counts differ).
			text = response.peekBody(MAX_LENGTH * 4L + 4).string();
		}
		catch (IOException e)
		{
			return "(unreadable: " + e.getMessage() + ")";
		}

		text = text.replaceAll("\\s+", " ").trim();
		if (text.isEmpty())
		{
			return "(empty)";
		}

		return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) + "... (truncated)" : text;
	}
}
