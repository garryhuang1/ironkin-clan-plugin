package com.ironkinclan.api;

import com.ironkinclan.config.IronkinClanConfig;
import javax.inject.Inject;
import okhttp3.MediaType;
import okhttp3.Request;

/**
 * Builds authenticated requests against the Ironkin server's events API, shared by
 * {@link com.ironkinclan.manager.TrackedItemManager} and
 * {@link com.ironkinclan.manager.DropSubmissionManager}.
 */
public class IronkinClanApiClient
{
	public static final String API_KEY_HEADER = "x-api-key";
	public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	private final IronkinClanConfig config;

	@Inject
	public IronkinClanApiClient(IronkinClanConfig config)
	{
		this.config = config;
	}

	public Request.Builder newItemListRequest()
	{
		return authenticatedRequest(baseUrl() + "/events/item-list");
	}

	public Request.Builder newSubmissionRequest(String eventId)
	{
		return authenticatedRequest(baseUrl() + "/events/" + eventId + "/submissions");
	}

	private Request.Builder authenticatedRequest(String url)
	{
		return new Request.Builder()
			.url(url)
			.header(API_KEY_HEADER, config.apiKey());
	}

	private String baseUrl()
	{
		String url = config.serverUrl();
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}
