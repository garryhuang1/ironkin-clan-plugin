package com.ironkinclan.api;

import com.ironkinclan.config.IronkinClanConfig;
import okhttp3.Request;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IronkinClanApiClientTest
{
	private IronkinClanConfig config;
	private IronkinClanApiClient apiClient;

	@Before
	public void setUp()
	{
		config = mock(IronkinClanConfig.class);
		when(config.serverUrl()).thenReturn("https://ironkin.example.com");
		when(config.apiKey()).thenReturn("secret-key");

		apiClient = new IronkinClanApiClient(config);
	}

	@Test
	public void newItemListRequest_buildsExpectedUrlAndAuthHeader()
	{
		Request request = apiClient.newItemListRequest().build();

		assertEquals("https://ironkin.example.com/events/item-list", request.url().toString());
		assertEquals("secret-key", request.header(IronkinClanApiClient.API_KEY_HEADER));
	}

	@Test
	public void newSubmissionRequest_includesEventIdInPath()
	{
		Request request = apiClient.newSubmissionRequest("bounty-123").build();

		assertEquals("https://ironkin.example.com/events/bounty-123/submissions", request.url().toString());
		assertEquals("secret-key", request.header(IronkinClanApiClient.API_KEY_HEADER));
	}

	@Test
	public void baseUrl_stripsTrailingSlashFromServerUrl()
	{
		when(config.serverUrl()).thenReturn("https://ironkin.example.com/");

		Request request = apiClient.newItemListRequest().build();

		assertEquals("https://ironkin.example.com/events/item-list", request.url().toString());
	}
}
