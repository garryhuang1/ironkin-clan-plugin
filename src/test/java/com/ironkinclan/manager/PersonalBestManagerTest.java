package com.ironkinclan.manager;

import com.ironkinclan.api.IronkinClanApiClient;
import com.ironkinclan.config.IronkinClanConfig;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PersonalBestManagerTest
{
	private static final String SUBMIT_URL = "https://ironkin.example.com/api/hall-of-flame/plugin-submit";

	private OkHttpClient httpClient;
	private Call call;
	private ScheduledExecutorService executor;
	private DiagnosticListener listener;
	private PersonalBestManager manager;

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
		executor = mock(ScheduledExecutorService.class);
		// Run scheduled retries synchronously and immediately so tests don't need to deal with
		// real delays - the backoff duration itself isn't the concern of these tests.
		doAnswer(invocation ->
		{
			Runnable retry = invocation.getArgument(0);
			retry.run();
			return null;
		}).when(executor).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

		manager = new PersonalBestManager(apiClient, httpClient, drawManager, executor);

		listener = mock(DiagnosticListener.class);
		manager.setListener(listener);
	}

	private void respondWith(int code)
	{
		doAnswer(invocation ->
		{
			Callback callback = invocation.getArgument(0);
			Response response = new Response.Builder()
				.request(new Request.Builder().url(SUBMIT_URL).build())
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

	private static String bodyText(Request request) throws IOException
	{
		Buffer buffer = new Buffer();
		request.body().writeTo(buffer);
		return buffer.readUtf8();
	}

	@Test
	public void uploadPersonalBest_success_postsToPluginSubmitUrlWithAuthHeader()
	{
		respondWith(200);

		manager.uploadPersonalBest("PlayerName", "Vardorvis", "1:23.40", testImage());

		ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
		verify(httpClient).newCall(requestCaptor.capture());
		Request request = requestCaptor.getValue();

		assertEquals(SUBMIT_URL, request.url().toString());
		assertEquals("secret-key", request.header(IronkinClanApiClient.PLUGIN_KEY_HEADER));

		verify(listener).onDiagnosticEvent(eq("Sent Vardorvis personal best (1:23.40) for PlayerName"), eq(true));
	}

	@Test
	public void uploadPersonalBest_requestBody_isMultipartWithAllFields() throws IOException
	{
		respondWith(200);

		manager.uploadPersonalBest("PlayerName", "Vardorvis", "1:23.40", testImage());

		ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
		verify(httpClient).newCall(requestCaptor.capture());
		Request request = requestCaptor.getValue();

		assertTrue(request.body().contentType().toString().startsWith("multipart/form-data"));

		String body = bodyText(request);
		assertTrue(body.contains("name=\"player\""));
		assertTrue(body.contains("PlayerName"));
		assertTrue(body.contains("name=\"boss\""));
		assertTrue(body.contains("Vardorvis"));
		assertTrue(body.contains("name=\"time\""));
		assertTrue(body.contains("1:23.40"));
		assertTrue(body.contains("name=\"proof\""));
		assertTrue(body.contains("filename=\"personal-best.png\""));
	}

	@Test
	public void uploadPersonalBest_httpError_notifiesListenerFalse()
	{
		respondWith(500);

		manager.uploadPersonalBest("PlayerName", "Vardorvis", "1:23.40", testImage());

		verify(listener).onDiagnosticEvent(any(String.class), eq(false));
	}

	@Test
	public void uploadPersonalBest_httpError_logsRequestFieldsWithoutScreenshotOrApiKey()
	{
		DiagnosticListener diagnosticListener = mock(DiagnosticListener.class);
		manager.setDiagnosticListener(diagnosticListener);
		respondWith(500);

		manager.uploadPersonalBest("PlayerName", "Vardorvis", "1:23.40", testImage());

		ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
		verify(diagnosticListener, times(2)).onDiagnosticEvent(textCaptor.capture(), eq(false));
		String text = textCaptor.getAllValues().get(0);
		assertEquals("Server response (HTTP 500): (empty)", textCaptor.getAllValues().get(1));

		assertTrue(text.startsWith("Failed request was POST " + SUBMIT_URL + " with body: "));
		assertTrue(text.contains("player=PlayerName, boss=Vardorvis, time=1:23.40, proof=[PNG, "));
		assertTrue(!text.contains("secret-key"));
	}

	@Test
	public void uploadPersonalBest_networkFailure_retriesTwiceThenNotifiesListenerFalse()
	{
		respondWithFailure(new IOException("connection refused"));

		manager.uploadPersonalBest("PlayerName", "Vardorvis", "1:23.40", testImage());

		// Initial attempt + 2 retries.
		verify(httpClient, times(3)).newCall(any(Request.class));
		verify(listener).onDiagnosticEvent(any(String.class), eq(false));
	}
}
