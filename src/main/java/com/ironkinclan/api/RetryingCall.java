package com.ironkinclan.api;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Enqueues an OkHttp request, retrying with an exponential backoff on network-level
 * failures (timeouts, connection resets, DNS failures, etc. - anything that reaches
 * {@link Callback#onFailure}). HTTP error responses (4xx/5xx) are not retried here: the
 * server received and processed the request, so an immediate retry is unlikely to help,
 * and callers already treat those as terminal failures.
 */
@Slf4j
public final class RetryingCall
{
	private static final int MAX_RETRIES = 2;
	private static final long INITIAL_BACKOFF_MS = 2000;

	private RetryingCall()
	{
	}

	public static void enqueue(OkHttpClient httpClient, ScheduledExecutorService executor, Request request, Callback callback)
	{
		attempt(httpClient, executor, request, callback, 0);
	}

	private static void attempt(OkHttpClient httpClient, ScheduledExecutorService executor, Request request, Callback callback, int retryCount)
	{
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				if (retryCount >= MAX_RETRIES)
				{
					callback.onFailure(call, e);
					return;
				}

				long delayMs = INITIAL_BACKOFF_MS << retryCount;
				log.debug("Request to {} failed ({}); retrying in {}ms (attempt {}/{})",
					request.url(), e.getMessage(), delayMs, retryCount + 1, MAX_RETRIES);
				executor.schedule(() -> attempt(httpClient, executor, request, callback, retryCount + 1),
					delayMs, TimeUnit.MILLISECONDS);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				callback.onResponse(call, response);
			}
		});
	}
}
