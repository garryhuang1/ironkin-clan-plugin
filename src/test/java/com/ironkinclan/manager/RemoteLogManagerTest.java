package com.ironkinclan.manager;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ironkinclan.api.IronkinClanApiClient;
import com.ironkinclan.config.IronkinClanConfig;
import com.ironkinclan.manager.RemoteLogListener.Level;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RemoteLogManagerTest
{
	private IronkinClanConfig config;
	private ConfigManager configManager;
	private OkHttpClient httpClient;
	private Call call;
	private RemoteLogManager manager;

	@Before
	public void setUp()
	{
		config = mock(IronkinClanConfig.class);
		when(config.serverUrl()).thenReturn("https://ironkin.example.com");
		when(config.apiKey()).thenReturn("secret-key");
		when(config.enableErrorReporting()).thenReturn(true);

		configManager = mock(ConfigManager.class);
		when(configManager.getConfiguration(IronkinClanConfig.CONFIG_GROUP, RemoteLogManager.INSTALL_ID_KEY))
			.thenReturn("install-1");

		httpClient = mock(OkHttpClient.class);
		call = mock(Call.class);
		when(httpClient.newCall(any(Request.class))).thenReturn(call);

		manager = new RemoteLogManager(config, configManager, new IronkinClanApiClient(config), httpClient,
			new Gson(), mock(ScheduledExecutorService.class));
	}

	private void respondWith(int code, String retryAfter)
	{
		doAnswer(invocation ->
		{
			Callback callback = invocation.getArgument(0);
			Response.Builder response = new Response.Builder()
				.request(new Request.Builder().url("https://logs.example.com/api/ingest").build())
				.protocol(Protocol.HTTP_1_1)
				.code(code)
				.message("")
				.body(ResponseBody.create(MediaType.get("application/json"), "{}"));
			if (retryAfter != null)
			{
				response.header("Retry-After", retryAfter);
			}
			callback.onResponse(call, response.build());
			return null;
		}).when(call).enqueue(any(Callback.class));
	}

	private Request sentRequest(int index)
	{
		ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
		verify(httpClient, atLeast(index + 1)).newCall(requestCaptor.capture());
		return requestCaptor.getAllValues().get(index);
	}

	private JsonObject sentBody(int index) throws IOException
	{
		Buffer buffer = new Buffer();
		sentRequest(index).body().writeTo(buffer);
		return new Gson().fromJson(buffer.readUtf8(), JsonObject.class);
	}

	@Test
	public void flush_postsQueuedEventsToIngestUrlWithoutApiKey() throws IOException
	{
		manager.log(Level.WARN, "Failed to fetch ember balance", Map.of("httpStatus", 503));
		manager.flush();

		Request request = sentRequest(0);
		assertEquals("POST", request.method());
		assertEquals(IronkinClanApiClient.LOG_SERVICE_URL + "/api/ingest", request.url().toString());
		assertNull(request.header(IronkinClanApiClient.API_KEY_HEADER));
		assertNull(request.header(IronkinClanApiClient.PLUGIN_KEY_HEADER));

		JsonObject body = sentBody(0);
		assertEquals("install-1", body.get("installId").getAsString());
		assertEquals(RemoteLogManager.PLUGIN_VERSION, body.get("pluginVersion").getAsString());

		JsonArray events = body.getAsJsonArray("events");
		assertEquals(1, events.size());
		JsonObject event = events.get(0).getAsJsonObject();
		assertEquals("warn", event.get("level").getAsString());
		assertEquals("Failed to fetch ember balance", event.get("message").getAsString());
		assertEquals(503, event.getAsJsonObject("context").get("httpStatus").getAsInt());
		// The endpoint rejects an empty stack, so it has to be absent rather than "" or null.
		assertFalse(event.has("stack"));
	}

	@Test
	public void flush_nothingQueued_sendsNothing()
	{
		manager.flush();

		verify(httpClient, never()).newCall(any(Request.class));
	}

	@Test
	public void log_whenReportingDisabled_queuesNothing()
	{
		when(config.enableErrorReporting()).thenReturn(false);
		manager.log(Level.ERROR, "Failed to upload drop", null);

		when(config.enableErrorReporting()).thenReturn(true);
		manager.flush();

		verify(httpClient, never()).newCall(any(Request.class));
	}

	@Test
	public void flush_whenReportingDisabledAfterQueueing_discardsQueue()
	{
		manager.log(Level.ERROR, "Failed to upload drop", null);

		when(config.enableErrorReporting()).thenReturn(false);
		manager.flush();
		when(config.enableErrorReporting()).thenReturn(true);
		manager.flush();

		verify(httpClient, never()).newCall(any(Request.class));
	}

	@Test
	public void flush_generatesAndStoresInstallIdWhenMissing() throws IOException
	{
		when(configManager.getConfiguration(IronkinClanConfig.CONFIG_GROUP, RemoteLogManager.INSTALL_ID_KEY))
			.thenReturn(null);

		manager.log(Level.INFO, "Plugin started", null);
		manager.flush();

		ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
		verify(configManager).setConfiguration(eq(IronkinClanConfig.CONFIG_GROUP), eq(RemoteLogManager.INSTALL_ID_KEY), idCaptor.capture());
		assertEquals(36, idCaptor.getValue().length());
		assertEquals(idCaptor.getValue(), sentBody(0).get("installId").getAsString());
	}

	@Test
	public void flush_existingInstallId_isNotRegenerated()
	{
		manager.log(Level.INFO, "Plugin started", null);
		manager.flush();

		verify(configManager, never()).setConfiguration(anyString(), anyString(), anyString());
	}

	@Test
	public void log_repeatedEvent_isSentOnceWithOccurrenceCount() throws IOException
	{
		for (int i = 0; i < 5; i++)
		{
			manager.log(Level.WARN, "Failed to fetch ember balance", Map.of("httpStatus", 503));
		}
		manager.flush();

		JsonArray events = sentBody(0).getAsJsonArray("events");
		assertEquals(1, events.size());
		JsonObject context = events.get(0).getAsJsonObject().getAsJsonObject("context");
		assertEquals(5, context.get("occurrences").getAsInt());
		assertEquals(503, context.get("httpStatus").getAsInt());
	}

	@Test
	public void logException_sendsStackTraceWithUnixLineEndings() throws IOException
	{
		manager.logException("Unexpected error while processing loot", new IllegalStateException("boom"), null);
		manager.flush();

		JsonObject event = sentBody(0).getAsJsonArray("events").get(0).getAsJsonObject();
		assertEquals("error", event.get("level").getAsString());
		String stack = event.get("stack").getAsString();
		assertTrue(stack.startsWith("java.lang.IllegalStateException: boom\n\tat "));
		assertFalse(stack.contains("\r"));
	}

	@Test
	public void log_overLongFields_areTruncatedToEndpointLimits() throws IOException
	{
		String longText = String.join("", Collections.nCopies(20000, "x"));
		manager.logException(longText, new IllegalStateException(longText), Map.of("detail", longText));
		manager.flush();

		JsonObject event = sentBody(0).getAsJsonArray("events").get(0).getAsJsonObject();
		assertEquals(RemoteLogManager.MAX_MESSAGE_LENGTH, event.get("message").getAsString().length());
		assertEquals(RemoteLogManager.MAX_STACK_LENGTH, event.get("stack").getAsString().length());
		assertTrue(event.getAsJsonObject("context").get("contextDropped").getAsBoolean());
		assertFalse(event.getAsJsonObject("context").has("detail"));
	}

	@Test
	public void flush_sendsAtMostFiftyEventsPerRequest() throws IOException
	{
		for (int i = 0; i < 60; i++)
		{
			manager.log(Level.INFO, "event " + i, null);
		}

		manager.flush();
		manager.flush();

		assertEquals(RemoteLogManager.MAX_EVENTS_PER_REQUEST, sentBody(0).getAsJsonArray("events").size());
		assertEquals(10, sentBody(1).getAsJsonArray("events").size());
	}

	@Test
	public void flush_keepsRequestBodyUnderEndpointLimit() throws IOException
	{
		String stackSizedMessage = String.join("", Collections.nCopies(17000, "x"));
		for (int i = 0; i < 30; i++)
		{
			manager.logException("event " + i, new IllegalStateException(stackSizedMessage), null);
		}

		manager.flush();

		Buffer buffer = new Buffer();
		sentRequest(0).body().writeTo(buffer);
		assertTrue(buffer.size() < 256 * 1024);
	}

	@Test
	public void log_queueFull_dropsOldestEvents() throws IOException
	{
		for (int i = 0; i < RemoteLogManager.MAX_QUEUED_EVENTS + 10; i++)
		{
			manager.log(Level.INFO, "event " + i, null);
		}

		manager.flush();

		JsonArray events = sentBody(0).getAsJsonArray("events");
		assertEquals("event 10", events.get(0).getAsJsonObject().get("message").getAsString());
	}

	@Test
	public void flush_rateLimited_holdsBatchUntilRetryAfter()
	{
		respondWith(429, "60");
		manager.log(Level.ERROR, "Failed to upload drop", null);

		manager.flush();
		manager.flush();

		// The second flush is inside the Retry-After window, so it must not hit the service again.
		verify(httpClient, times(1)).newCall(any(Request.class));
	}

	@Test
	public void flush_serverError_dropsBatch()
	{
		respondWith(500, null);
		manager.log(Level.ERROR, "Failed to upload drop", null);

		manager.flush();
		manager.flush();

		verify(httpClient, times(1)).newCall(any(Request.class));
	}
}
