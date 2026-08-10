package com.ironkinclan.manager;

import com.google.gson.Gson;
import com.ironkinclan.api.IronkinClanApiClient;
import com.ironkinclan.config.IronkinClanConfig;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.client.ui.DrawManager;
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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DropSubmissionManagerTest
{
	private OkHttpClient httpClient;
	private Call call;
	private DiagnosticListener listener;
	private DropSubmissionManager manager;

	@Before
	public void setUp()
	{
		IronkinClanConfig config = mock(IronkinClanConfig.class);
		when(config.serverUrl()).thenReturn("https://ironkin.example.com");
		when(config.apiKey()).thenReturn("secret-key");

		IronkinClanApiClient apiClient = new IronkinClanApiClient(config);
		httpClient = mock(OkHttpClient.class);
		call = mock(Call.class);
		when(httpClient.newCall(any(Request.class))).thenReturn(call);

		DrawManager drawManager = mock(DrawManager.class);
		ScheduledExecutorService executor = mock(ScheduledExecutorService.class);

		manager = new DropSubmissionManager(apiClient, httpClient, new Gson(), drawManager, executor);

		listener = mock(DiagnosticListener.class);
		manager.setListener(listener);
	}

	private void respondWith(int code)
	{
		doAnswer(invocation ->
		{
			Callback callback = invocation.getArgument(0);
			Response response = new Response.Builder()
				.request(new Request.Builder().url("https://ironkin.example.com/events/bounty-123/submissions").build())
				.protocol(Protocol.HTTP_1_1)
				.code(code)
				.message(code == 200 ? "OK" : "Error")
				.body(ResponseBody.create(MediaType.get("text/plain"), ""))
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

	private static BufferedImage testImage()
	{
		return new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
	}

	@Test
	public void uploadDrop_success_postsToEventSubmissionUrlAndNotifiesListenerTrue()
	{
		respondWith(200);

		manager.uploadDrop("bounty-123", "PlayerName", 20997, "Twisted bow", 1720280000000L, testImage());

		ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
		verify(httpClient).newCall(requestCaptor.capture());
		Request request = requestCaptor.getValue();

		assertEquals("https://ironkin.example.com/events/bounty-123/submissions", request.url().toString());
		assertEquals("secret-key", request.header(IronkinClanApiClient.API_KEY_HEADER));

		verify(listener).onDiagnosticEvent(any(String.class), eq(true));
	}

	@Test
	public void uploadDrop_requestBody_containsDropFields() throws IOException
	{
		respondWith(200);

		manager.uploadDrop("bounty-123", "PlayerName", 20997, "Twisted bow", 1720280000000L, testImage());

		ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
		verify(httpClient).newCall(requestCaptor.capture());
		Request request = requestCaptor.getValue();

		Buffer buffer = new Buffer();
		request.body().writeTo(buffer);
		String json = buffer.readUtf8();

		assertTrue(json.contains("\"username\":\"PlayerName\""));
		assertTrue(json.contains("\"itemid\":20997"));
		assertTrue(json.contains("\"timestamp\":1720280000000"));
		assertTrue(json.contains("\"imageData\":\""));
	}

	@Test
	public void uploadDrop_httpError_notifiesListenerFalse()
	{
		respondWith(500);

		manager.uploadDrop("bounty-123", "PlayerName", 20997, "Twisted bow", 1720280000000L, testImage());

		verify(listener).onDiagnosticEvent(any(String.class), eq(false));
	}

	@Test
	public void uploadDrop_networkFailure_notifiesListenerFalse()
	{
		respondWithFailure(new IOException("connection refused"));

		manager.uploadDrop("bounty-123", "PlayerName", 20997, "Twisted bow", 1720280000000L, testImage());

		verify(listener).onDiagnosticEvent(any(String.class), eq(false));
	}

	@Test
	public void uploadDrop_differentEventIds_postToDifferentUrls()
	{
		respondWith(200);

		manager.uploadDrop("bounty-123", "PlayerName", 20997, "Twisted bow", 1720280000000L, testImage());
		manager.uploadDrop("botw-123", "PlayerName", 20997, "Twisted bow", 1720280000000L, testImage());

		ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
		verify(httpClient, times(2)).newCall(requestCaptor.capture());

		List<Request> requests = requestCaptor.getAllValues();
		assertTrue(requests.get(0).url().toString().endsWith("/events/bounty-123/submissions"));
		assertTrue(requests.get(1).url().toString().endsWith("/events/botw-123/submissions"));
	}
}
