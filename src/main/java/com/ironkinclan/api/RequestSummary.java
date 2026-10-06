package com.ironkinclan.api;

import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.Request;

/**
 * Describes a failed request for the remote logging service: method, URL and (when given) the
 * body fields. Headers are never included, because they carry the API key, and callers must leave
 * any image field and any player name out of the body they pass in.
 */
public final class RequestSummary
{
	private RequestSummary()
	{
	}

	// body may be null for requests that have none.
	public static Map<String, Object> of(Request request, Map<String, Object> body)
	{
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("method", request.method());
		summary.put("url", request.url().toString());
		if (body != null)
		{
			summary.put("body", body);
		}
		return summary;
	}
}
