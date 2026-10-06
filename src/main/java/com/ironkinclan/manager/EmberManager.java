package com.ironkinclan.manager;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.ironkinclan.api.IronkinClanApiClient;
import com.ironkinclan.api.RequestSummary;
import com.ironkinclan.api.ResponsePreview;
import com.ironkinclan.config.IronkinClanConfig;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Polls the member's ember balance on a fixed interval. Unlike {@link TrackedItemManager}, this
 * intentionally does not use {@link com.ironkinclan.api.RetryingCall}: a failed poll is
 * superseded by the next scheduled poll a few minutes later anyway, so retrying just adds delay
 * without adding value.
 */
@Slf4j
public class EmberManager
{
	private static final long POLL_INTERVAL_MINUTES = 5;

	public interface Listener
	{
		// delta is the change since the last successful poll, or null if this is the first poll
		// or the balance hasn't changed.
		void onEmberBalanceUpdated(int balance, Integer delta);
	}

	private final IronkinClanConfig config;
	private final IronkinClanApiClient apiClient;
	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;

	private volatile Integer lastBalance;
	private ScheduledFuture<?> pollTask;

	private Listener listener;
	private DiagnosticListener diagnosticListener;
	private RemoteLogListener remoteLogListener;

	@Inject
	public EmberManager(IronkinClanConfig config, IronkinClanApiClient apiClient, OkHttpClient httpClient,
		Gson gson, ScheduledExecutorService executor)
	{
		this.config = config;
		this.apiClient = apiClient;
		this.httpClient = httpClient;
		this.gson = gson;
		this.executor = executor;
	}

	public void setListener(Listener listener)
	{
		this.listener = listener;
	}

	public void setDiagnosticListener(DiagnosticListener diagnosticListener)
	{
		this.diagnosticListener = diagnosticListener;
	}

	public void setRemoteLogListener(RemoteLogListener remoteLogListener)
	{
		this.remoteLogListener = remoteLogListener;
	}

	public void start()
	{
		stop();
		fetch();
		pollTask = executor.scheduleAtFixedRate(this::fetch, POLL_INTERVAL_MINUTES, POLL_INTERVAL_MINUTES, TimeUnit.MINUTES);
	}

	public void stop()
	{
		if (pollTask != null)
		{
			pollTask.cancel(false);
			pollTask = null;
		}
	}

	public void reset()
	{
		lastBalance = null;
	}

	public void fetch()
	{
		if (config.serverUrl().isEmpty() || config.apiKey().isEmpty())
		{
			return;
		}

		Request request = apiClient.newEmberBalanceRequest().build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				warn("Failed to fetch Ironkin ember balance: " + e.getMessage(), e);
				notifyRemote(RemoteLogListener.Level.WARN, "Failed to fetch ember balance",
					Map.of("exception", e.toString(), "request", RequestSummary.of(request, null)));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				EmberBalanceResponse body;
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						String serverResponse = ResponsePreview.of(r);
						warn("Failed to fetch Ironkin ember balance: HTTP " + r.code() + " - server response: " + serverResponse);
						notifyRemote(RemoteLogListener.Level.WARN, "Ember balance fetch returned an HTTP error",
							Map.of("httpStatus", r.code(), "request", RequestSummary.of(request, null), "response", serverResponse));
						return;
					}

					body = gson.fromJson(r.body().charStream(), EmberBalanceResponse.class);
					if (body == null)
					{
						warn("Ironkin ember balance response was empty or malformed");
						notifyRemote(RemoteLogListener.Level.WARN, "Ember balance response was empty or malformed", null);
						return;
					}
				}
				catch (JsonSyntaxException e)
				{
					warn("Failed to parse Ironkin ember balance: " + e.getMessage(), e);
					notifyRemote(RemoteLogListener.Level.WARN, "Failed to parse ember balance", Map.of("exception", e.toString()));
					return;
				}

				Integer previousBalance = lastBalance;
				lastBalance = body.balance;

				Integer delta = (previousBalance != null && previousBalance != body.balance)
					? body.balance - previousBalance
					: null;

				if (diagnosticListener != null)
				{
					diagnosticListener.onDiagnosticEvent(
						"Ember balance updated: " + body.balance + " (previous: " + previousBalance + ")", true);
				}

				if (listener != null)
				{
					listener.onEmberBalanceUpdated(body.balance, delta);
				}
			}
		});
	}

	private void warn(String message)
	{
		log.warn(message);
		if (diagnosticListener != null)
		{
			diagnosticListener.onDiagnosticEvent(message, false);
		}
	}

	private void warn(String message, Throwable t)
	{
		log.warn(message, t);
		if (diagnosticListener != null)
		{
			diagnosticListener.onDiagnosticEvent(message, false);
		}
	}

	private void notifyRemote(RemoteLogListener.Level level, String message, Map<String, Object> context)
	{
		if (remoteLogListener != null)
		{
			remoteLogListener.onRemoteLogEvent(level, message, context);
		}
	}

	private static class EmberBalanceResponse
	{
		int balance;
	}
}
