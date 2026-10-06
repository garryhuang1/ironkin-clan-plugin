package com.ironkinclan.manager;

import com.google.gson.Gson;
import com.ironkinclan.api.IronkinClanApiClient;
import com.ironkinclan.config.IronkinClanConfig;
import com.ironkinclan.manager.RemoteLogListener.Level;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Queues errors and log events in memory and sends them to the logging service in batches.
 * Opt-in (see {@link IronkinClanConfig#enableErrorReporting()}) and deliberately silent: a failure
 * to send is only ever written to {@code log.debug}, never to the panel log or chat, and never
 * fed back into this manager.
 *
 * Like {@link EmberManager}, this does not use {@link com.ironkinclan.api.RetryingCall}: a batch
 * that fails to send is dropped, except on HTTP 429 where it is kept for after the Retry-After.
 */
@Slf4j
public class RemoteLogManager
{
	// Keep in sync with "version" in runelite-plugin.properties, which isn't readable at runtime.
	static final String PLUGIN_VERSION = "1.02";
	static final String INSTALL_ID_KEY = "installId";

	// Limits imposed by the ingest endpoint. One over-long field rejects the whole batch.
	static final int MAX_EVENTS_PER_REQUEST = 50;
	static final int MAX_MESSAGE_LENGTH = 2000;
	static final int MAX_STACK_LENGTH = 16000;
	private static final int MAX_CAUSE_DEPTH = 5;
	// The endpoint allows 4,000; the margin leaves room for the "occurrences" entry added on send.
	private static final int MAX_CONTEXT_LENGTH = 3900;
	// The endpoint allows 256 KB for the whole body; the margin covers the envelope.
	private static final int MAX_BODY_BYTES = 200_000;

	static final int MAX_QUEUED_EVENTS = 200;
	private static final long FLUSH_INTERVAL_SECONDS = 30;
	private static final long DEFAULT_RETRY_AFTER_SECONDS = 60;
	private static final long MAX_RETRY_AFTER_SECONDS = 3600;

	private final IronkinClanConfig config;
	private final ConfigManager configManager;
	private final IronkinClanApiClient apiClient;
	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;

	// Keyed by level + message + stack, so a repeat of an event that is still waiting to be sent
	// bumps its occurrence count instead of queueing again. Insertion order doubles as queue order.
	private final Map<String, PendingEvent> pending = new LinkedHashMap<>();
	private volatile long pausedUntilMillis;
	private ScheduledFuture<?> flushTask;

	@Inject
	public RemoteLogManager(IronkinClanConfig config, ConfigManager configManager, IronkinClanApiClient apiClient,
		OkHttpClient httpClient, Gson gson, ScheduledExecutorService executor)
	{
		this.config = config;
		this.configManager = configManager;
		this.apiClient = apiClient;
		this.httpClient = httpClient;
		this.gson = gson;
		this.executor = executor;
	}

	public void start()
	{
		cancelFlushTask();
		flushTask = executor.scheduleWithFixedDelay(this::flush, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	// Sends whatever is still queued on the way out. enqueue() only hands the request to the
	// OkHttp pool, so this doesn't block shutdown.
	public void stop()
	{
		cancelFlushTask();
		flush();
		synchronized (pending)
		{
			pending.clear();
		}
	}

	private void cancelFlushTask()
	{
		if (flushTask != null)
		{
			flushTask.cancel(false);
			flushTask = null;
		}
	}

	// context may be null. See RemoteLogListener for what is allowed in message and context.
	public void log(Level level, String message, Map<String, Object> context)
	{
		enqueue(level, message, null, context);
	}

	// Only for exceptions thrown from this plugin's own code. The service groups events that have
	// a stack by exception type and top frames alone, so an IOException out of OkHttp would land in
	// the same group no matter which request failed - report those with log() and the exception
	// text in the context instead.
	public void logException(String message, Throwable cause, Map<String, Object> context)
	{
		// Logged locally whether or not reporting is enabled, so the stack trace is never lost.
		log.warn(message, cause);
		enqueue(Level.ERROR, message, cause, context);
	}

	private void enqueue(Level level, String message, Throwable cause, Map<String, Object> context)
	{
		try
		{
			if (!isEnabled())
			{
				return;
			}

			PendingEvent event = new PendingEvent();
			event.level = level.name().toLowerCase(Locale.ROOT);
			event.message = truncate(message == null || message.isEmpty() ? "(no message)" : message, MAX_MESSAGE_LENGTH);
			event.stack = cause == null ? null : truncate(stackTraceOf(cause), MAX_STACK_LENGTH);
			event.context = boundedContext(context);

			String key = event.level + '\n' + event.message + '\n' + event.stack;
			synchronized (pending)
			{
				add(key, event);
			}
		}
		catch (RuntimeException e)
		{
			log.debug("Failed to queue remote log event", e);
		}
	}

	// Caller must hold the lock on pending.
	private void add(String key, PendingEvent event)
	{
		PendingEvent queued = pending.get(key);
		if (queued != null)
		{
			queued.occurrences += event.occurrences;
			return;
		}

		if (pending.size() >= MAX_QUEUED_EVENTS)
		{
			Iterator<String> oldest = pending.keySet().iterator();
			oldest.next();
			oldest.remove();
		}
		pending.put(key, event);
	}

	// Package-private (rather than private) so unit tests can trigger a send directly instead of
	// driving the scheduled task.
	void flush()
	{
		// Anything thrown out of a scheduleWithFixedDelay task silently cancels every later run.
		try
		{
			if (!isEnabled())
			{
				synchronized (pending)
				{
					pending.clear();
				}
				return;
			}

			if (System.currentTimeMillis() < pausedUntilMillis)
			{
				return;
			}

			List<PendingEvent> batch = new ArrayList<>();
			List<LogEvent> events = new ArrayList<>();
			int bodyBytes = 0;
			synchronized (pending)
			{
				Iterator<PendingEvent> it = pending.values().iterator();
				while (it.hasNext() && batch.size() < MAX_EVENTS_PER_REQUEST)
				{
					PendingEvent next = it.next();
					LogEvent event = new LogEvent(next);
					int eventBytes = gson.toJson(event).getBytes(StandardCharsets.UTF_8).length;
					if (!batch.isEmpty() && bodyBytes + eventBytes > MAX_BODY_BYTES)
					{
						break;
					}

					it.remove();
					batch.add(next);
					events.add(event);
					bodyBytes += eventBytes;
				}
			}

			if (batch.isEmpty())
			{
				return;
			}

			IngestRequest body = new IngestRequest(installId(), PLUGIN_VERSION, events);
			Request request = apiClient.newLogIngestRequest()
				.post(RequestBody.create(IronkinClanApiClient.JSON, gson.toJson(body)))
				.build();

			httpClient.newCall(request).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					log.debug("Failed to send {} remote log event(s); dropping them", batch.size(), e);
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					try (Response r = response)
					{
						if (r.code() == 429)
						{
							long retryAfterSeconds = retryAfterSeconds(r);
							pausedUntilMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(retryAfterSeconds);
							requeue(batch);
							log.debug("Remote log service is rate limiting; holding events for {}s", retryAfterSeconds);
						}
						else if (!r.isSuccessful())
						{
							log.debug("Remote log service rejected {} event(s) with HTTP {}; dropping them", batch.size(), r.code());
						}
					}
				}
			});
		}
		catch (RuntimeException e)
		{
			log.debug("Failed to send remote log events", e);
		}
	}

	private void requeue(List<PendingEvent> batch)
	{
		synchronized (pending)
		{
			for (PendingEvent event : batch)
			{
				add(event.level + '\n' + event.message + '\n' + event.stack, event);
			}
		}
	}

	private boolean isEnabled()
	{
		return config.enableErrorReporting();
	}

	// A random ID generated once per RuneLite profile, so the service can tell installs apart
	// without being told anything about the player.
	private String installId()
	{
		String installId = configManager.getConfiguration(IronkinClanConfig.CONFIG_GROUP, INSTALL_ID_KEY);
		if (installId == null || installId.isEmpty())
		{
			installId = UUID.randomUUID().toString();
			configManager.setConfiguration(IronkinClanConfig.CONFIG_GROUP, INSTALL_ID_KEY, installId);
		}
		return installId;
	}

	private Map<String, Object> boundedContext(Map<String, Object> context)
	{
		Map<String, Object> bounded = new LinkedHashMap<>();
		if (context == null || context.isEmpty())
		{
			return bounded;
		}

		if (gson.toJson(context).length() > MAX_CONTEXT_LENGTH)
		{
			bounded.put("contextDropped", true);
		}
		else
		{
			bounded.putAll(context);
		}
		return bounded;
	}

	private static long retryAfterSeconds(Response response)
	{
		String header = response.header("Retry-After");
		if (header == null)
		{
			return DEFAULT_RETRY_AFTER_SECONDS;
		}

		try
		{
			return Math.max(1, Math.min(MAX_RETRY_AFTER_SECONDS, Long.parseLong(header.trim())));
		}
		catch (NumberFormatException e)
		{
			return DEFAULT_RETRY_AFTER_SECONDS;
		}
	}

	// The text the ingest endpoint expects in "stack": the exception, then one "\tat frame" line
	// per frame, then the same for each cause. Always \n-separated, because the service
	// fingerprints the trace line by line.
	private static String stackTraceOf(Throwable cause)
	{
		StringBuilder stack = new StringBuilder();
		Throwable current = cause;
		// Depth-limited so a cause chain that loops back on itself can't spin forever.
		for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++)
		{
			if (depth > 0)
			{
				stack.append("Caused by: ");
			}
			stack.append(current).append('\n');
			for (StackTraceElement frame : current.getStackTrace())
			{
				stack.append("\tat ").append(frame).append('\n');
			}
			current = current.getCause();
		}
		return stack.toString().trim();
	}

	private static String truncate(String text, int maxLength)
	{
		return text.length() <= maxLength ? text : text.substring(0, maxLength);
	}

	private static class PendingEvent
	{
		String level;
		String message;
		String stack;
		Map<String, Object> context;
		int occurrences = 1;
	}

	private static class LogEvent
	{
		final String level;
		final String message;
		// Left null (and so omitted from the JSON) when absent: the endpoint rejects an empty stack.
		final String stack;
		final Map<String, Object> context;

		LogEvent(PendingEvent event)
		{
			this.level = event.level;
			this.message = event.message;
			this.stack = event.stack;

			Map<String, Object> context = new LinkedHashMap<>(event.context);
			if (event.occurrences > 1)
			{
				context.put("occurrences", event.occurrences);
			}
			this.context = context.isEmpty() ? null : context;
		}
	}

	private static class IngestRequest
	{
		final String installId;
		final String pluginVersion;
		final List<LogEvent> events;

		IngestRequest(String installId, String pluginVersion, List<LogEvent> events)
		{
			this.installId = installId;
			this.pluginVersion = pluginVersion;
			this.events = events;
		}
	}
}
