package com.ironkinclan;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TrackedItemManagerTest
{
	private IronkinClanConfig config;
	private OkHttpClient httpClient;
	private ItemManager itemManager;
	private ClientThread clientThread;
	private Call call;
	private TrackedItemManager manager;

	private DiagnosticListener diagnosticListener;
	private TrackedItemManager.Listener listener;

	@Before
	public void setUp()
	{
		config = mock(IronkinClanConfig.class);
		when(config.serverUrl()).thenReturn("https://ironkin.example.com");
		when(config.apiKey()).thenReturn("secret-key");

		IronkinClanApiClient apiClient = new IronkinClanApiClient(config);
		httpClient = mock(OkHttpClient.class);
		itemManager = mock(ItemManager.class);
		clientThread = mock(ClientThread.class);
		call = mock(Call.class);

		// fetch() resolves item names inside clientThread.invoke(); run it synchronously so
		// tests can assert on the result immediately.
		doAnswer(invocation ->
		{
			Runnable runnable = invocation.getArgument(0);
			runnable.run();
			return null;
		}).when(clientThread).invoke(any(Runnable.class));

		when(httpClient.newCall(any(Request.class))).thenReturn(call);

		manager = new TrackedItemManager(config, apiClient, httpClient, new Gson(), itemManager, clientThread);

		diagnosticListener = mock(DiagnosticListener.class);
		listener = mock(TrackedItemManager.Listener.class);
		manager.setDiagnosticListener(diagnosticListener);
		manager.setListener(listener);

		nameItem(20997, "Twisted bow");
		nameItem(11840, "Dragon warhammer");
	}

	private void nameItem(int itemId, String name)
	{
		ItemComposition composition = mock(ItemComposition.class);
		when(composition.getName()).thenReturn(name);
		when(itemManager.getItemComposition(itemId)).thenReturn(composition);
	}

	private void respondWithSuccess(String json)
	{
		respondWith(200, json);
	}

	private void respondWith(int code, String body)
	{
		doAnswer(invocation ->
		{
			Callback callback = invocation.getArgument(0);
			Response response = new Response.Builder()
				.request(new Request.Builder().url("https://ironkin.example.com/events/item-list").build())
				.protocol(Protocol.HTTP_1_1)
				.code(code)
				.message(code == 200 ? "OK" : "Error")
				.body(ResponseBody.create(MediaType.get("application/json; charset=utf-8"), body))
				.build();
			callback.onResponse(call, response);
			return null;
		}).when(call).enqueue(any(Callback.class));
	}

	private void respondWithFailure(IOException exception)
	{
		doAnswer(invocation ->
		{
			Callback callback = invocation.getArgument(0);
			callback.onFailure(call, exception);
			return null;
		}).when(call).enqueue(any(Callback.class));
	}

	@Test
	public void fetch_missingServerUrl_skipsRequestAndNotifiesDiagnostic()
	{
		when(config.serverUrl()).thenReturn("");

		manager.fetch();

		verify(httpClient, never()).newCall(any(Request.class));
		verify(diagnosticListener).onDiagnosticEvent(anyString(), eq(false));
	}

	@Test
	public void fetch_missingApiKey_skipsRequestAndNotifiesDiagnostic()
	{
		when(config.apiKey()).thenReturn("");

		manager.fetch();

		verify(httpClient, never()).newCall(any(Request.class));
		verify(diagnosticListener).onDiagnosticEvent(anyString(), eq(false));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void fetch_successfulResponse_populatesTrackedItemsAndNotifiesListener()
	{
		respondWithSuccess("{ \"events\": ["
			+ "{ \"eventId\": \"bounty-123\", \"items\": [20997] },"
			+ "{ \"eventId\": \"botw-123\", \"items\": [11840, 20997] }"
			+ "] }");

		manager.fetch();

		assertTrue(manager.hasTrackedItems());
		assertEquals("Twisted bow", manager.getItemName(20997));
		assertEquals("Dragon warhammer", manager.getItemName(11840));

		ArgumentCaptor<List<TrackedEventGroup>> captor = ArgumentCaptor.forClass(List.class);
		verify(listener).onTrackedItemsUpdated(captor.capture());
		assertEquals(2, captor.getValue().size());
	}

	@Test
	public void fetch_itemTrackedByMultipleEvents_getEventIdsForItemReturnsBoth()
	{
		respondWithSuccess("{ \"events\": ["
			+ "{ \"eventId\": \"bounty-123\", \"items\": [20997] },"
			+ "{ \"eventId\": \"botw-123\", \"items\": [11840, 20997] }"
			+ "] }");

		manager.fetch();

		List<String> eventIds = manager.getEventIdsForItem(20997);
		assertEquals(2, eventIds.size());
		assertTrue(eventIds.contains("bounty-123"));
		assertTrue(eventIds.contains("botw-123"));
		assertEquals(Collections.singletonList("botw-123"), manager.getEventIdsForItem(11840));
	}

	@Test
	public void fetch_itemNotTracked_getEventIdsForItemReturnsEmpty()
	{
		respondWithSuccess("{ \"events\": [ { \"eventId\": \"bounty-123\", \"items\": [20997] } ] }");

		manager.fetch();

		assertTrue(manager.getEventIdsForItem(999).isEmpty());
	}

	@Test
	public void fetch_httpError_notifiesDiagnosticAndAllowsRetry()
	{
		respondWith(500, "");

		manager.fetch();

		verify(diagnosticListener).onDiagnosticEvent(anyString(), eq(false));
		assertFalse(manager.hasTrackedItems());

		// A failed fetch should reset the in-flight flag so a subsequent fetch is allowed.
		respondWithSuccess("{ \"events\": [ { \"eventId\": \"bounty-123\", \"items\": [20997] } ] }");
		manager.fetch();

		assertTrue(manager.hasTrackedItems());
	}

	@Test
	public void fetch_emptyResponseBody_notifiesDiagnosticWithoutCrashing()
	{
		respondWithSuccess("");

		manager.fetch();

		verify(diagnosticListener).onDiagnosticEvent(anyString(), eq(false));
		assertFalse(manager.hasTrackedItems());
	}

	@Test
	public void fetch_responseWrongJsonType_notifiesDiagnosticWithoutCrashing()
	{
		// Valid JSON, but the wrong shape (array instead of object) - triggers Gson's
		// JsonSyntaxException path rather than the "empty/malformed" null-body path.
		respondWithSuccess("[1, 2, 3]");

		manager.fetch();

		verify(diagnosticListener).onDiagnosticEvent(anyString(), eq(false));
		assertFalse(manager.hasTrackedItems());
	}

	@Test
	public void fetch_emptyEventsList_treatsAsEmptyTrackedItems()
	{
		respondWithSuccess("{ \"events\": [] }");

		manager.fetch();

		assertFalse(manager.hasTrackedItems());
		verify(listener).onTrackedItemsUpdated(Collections.emptyList());
	}

	@Test
	public void fetch_networkFailure_notifiesDiagnostic()
	{
		respondWithFailure(new IOException("boom"));

		manager.fetch();

		verify(diagnosticListener).onDiagnosticEvent(anyString(), eq(false));
		assertFalse(manager.hasTrackedItems());
	}

	@Test
	public void fetch_whileRequestInFlight_doesNotIssueSecondCall()
	{
		// Deliberately leave call.enqueue(...) unstubbed (a no-op) to simulate a request that
		// hasn't completed yet.
		manager.fetch();
		manager.fetch();

		verify(httpClient, times(1)).newCall(any(Request.class));
	}

	@Test
	public void reset_clearsTrackedState()
	{
		respondWithSuccess("{ \"events\": [ { \"eventId\": \"bounty-123\", \"items\": [20997] } ] }");
		manager.fetch();
		assertTrue(manager.hasTrackedItems());

		manager.reset();

		assertFalse(manager.hasTrackedItems());
		assertTrue(manager.getEventIdsForItem(20997).isEmpty());
	}
}
