package com.ironkinclan.manager;

import java.util.Map;

/**
 * Receives events destined for the remote logging service. Unlike {@link DiagnosticListener},
 * whose free-form text stays on the user's machine, everything passed here leaves it, so:
 * the message must be a constant (the service groups on it), the variable parts go in the
 * context, and neither may identify a player - no RuneScape names and no chat content.
 */
public interface RemoteLogListener
{
	enum Level
	{
		ERROR,
		WARN,
		INFO,
		DEBUG
	}

	// context may be null.
	void onRemoteLogEvent(Level level, String message, Map<String, Object> context);
}
