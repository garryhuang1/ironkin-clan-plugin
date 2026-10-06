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
	public static final String PLUGIN_KEY_HEADER = "X-Ironkin-Plugin-Key";
	public static final String LOG_SERVICE_URL = "https://ironkin-logging-platform.garryhuang2.workers.dev";
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

	public Request.Builder newEmberBalanceRequest()
	{
		return authenticatedRequest(baseUrl() + "/api/embers/me");
	}

	public Request.Builder newSubmissionRequest(String eventId)
	{
		return authenticatedRequest(baseUrl() + "/events/" + eventId + "/submissions");
	}

	// Separate from authenticatedRequest(): the Hall of Flame plugin-submit endpoint
	// authenticates with a differently-named header than the rest of the Ironkin events API.
	public Request.Builder newPersonalBestRequest()
	{
		return new Request.Builder()
			.url(baseUrl() + "/api/hall-of-flame/plugin-submit")
			.header(PLUGIN_KEY_HEADER, config.apiKey());
	}

	// The logging service is a separate server from the Ironkin one and its ingest endpoint is
	// unauthenticated, so this must never carry the API key. Its URL is fixed rather than
	// configurable: error reports should only ever go to the one service the setting discloses.
	public Request.Builder newLogIngestRequest()
	{
		return new Request.Builder()
			.url(LOG_SERVICE_URL + "/api/ingest");
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
