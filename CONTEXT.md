# Ironkin Clan Plugin — Codebase Context

Project-specific context for agents working in this repo. `AGENTS.md` holds the generic RuneLite
plugin-hub rules; this file describes how *this* plugin is built. Where the two overlap, AGENTS.md
wins. Keep this file current when you change the architecture, the server contract, or the config.

## What the plugin does

A RuneLite plugin-hub plugin for the Ironkin clan. It talks to the clan's own server (URL and API
key supplied by the user) and has four features:

| Feature | Trigger | Gate | Server call |
| --- | --- | --- | --- |
| Tracked item drops | `LootReceived` (from the built-in Loot Tracker plugin) | `enableDropTracking` | `POST /events/{eventId}/submissions` |
| Personal bests (Hall of Flame) | `ChatMessage` containing "new personal best" | `enablePersonalBestTracking` | `POST /api/hall-of-flame/plugin-submit` |
| Ember balance | 5-minute poll while logged in, plus panel open | URL + API key set | `GET /api/embers/me` |
| Event passwords overlay | Arrives with the tracked item list | `showEventPasswords` (and drop tracking, since it rides on the item list) | none of its own |
| Error reporting | Failures in the features above; 30-second batch send | `enableErrorReporting` | `POST /api/ingest` on the **logging service**, a different server |

## Layout

```
src/main/java/com/ironkinclan/
  IronkinClanPlugin.java        Plugin entry point: lifecycle, event subscribers, orchestration
  config/IronkinClanConfig.java Config interface, group "ironkin-clan"
  api/
    IronkinClanApiClient.java   Builds authenticated Request.Builders; owns every URL and header
    RetryingCall.java           OkHttp enqueue with exponential backoff on network failures
    ResponsePreview.java        Bounded one-line excerpt of a response body, for the log
    RequestSummary.java         Method, URL and body fields of a failed request, for the remote log
  manager/
    TrackedItemManager.java     Fetches and caches event -> item IDs, resolves item names
    DropSubmissionManager.java  Screenshot + JSON upload of a tracked drop
    PersonalBestManager.java    Screenshot + multipart upload of a personal best
    PersonalBestMessageParser.java  Pure: extracts the time from a PB chat message
    CurrentBossTracker.java     Remembers the last tracked boss the player fought
    EmberManager.java           Polls the ember balance on a schedule
    ClanMemberManager.java      Counts nearby players and nearby clan members
    RaidPartyTracker.java       Remembers the clan members in the current raid party
    GroupComposition.java       Value object returned by ClanMemberManager
    DiagnosticListener.java     (text, success) callback shared by the managers
    RemoteLogManager.java       Queues, dedupes and batch-sends events to the logging service
    RemoteLogListener.java      (level, message, context) callback for events that leave the machine
  model/
    BossActivity.java           Enum: Hall of Flame category name <-> NPC display names
    TrackedEventGroup.java      eventId + items + eventPassword
    TrackedItem.java            id + name
  ui/
    IronkinClanPanel.java       Sidebar panel: embers, tracked items by event, log
    EventPasswordOverlay.java   In-game OverlayPanel listing event passwords
src/test/java/com/ironkinclan/  JUnit 4 + Mockito; mirrors the main package layout
  IronkinClanPluginTest.java    Not a test: the main() that launches the dev client
```

## Architecture

**The plugin class is the only orchestrator.** `IronkinClanPlugin` owns every `@Subscribe` handler
and all cross-feature wiring. Managers never reference each other, the panel, or the plugin; they
report outward through listener interfaces (`TrackedItemManager.Listener`, `EmberManager.Listener`,
`DiagnosticListener`) that the plugin sets in `startUp()` and nulls in `shutDown()`. Follow this
when adding a feature: a new manager with constructor injection and a listener, wired in the plugin.

**Dependency injection.** Managers, the API client and the overlay use Guice constructor injection
(`@Inject` on the constructor, final fields), which is what makes them unit-testable with mocks.
The plugin class uses field injection, as RuneLite plugins conventionally do. `IronkinClanPanel` is
built by hand in `startUp()`.

**Two output channels for user feedback**, both in the plugin class:

- `logUploadEvent` writes to the panel log *and* game chat. Used for submission results.
- `logDiagnostic` writes to the panel log only. Used for background and setup issues, and for
  "why didn't this submit" explanations. Do not route these to chat.

The panel records every entry regardless of `showDebugLog`; that setting only hides the section.

**Diagnostics go to the panel log, not slf4j `log.debug`.** This is a project decision that
overrides the AGENTS.md logging default: a clan member can export the panel log and send it over,
whereas debug output never leaves their machine. Noisy is acceptable. So trace-level detail
(loot events seen, screenshot encoding, retries, ember polls) is reported through a
`DiagnosticListener` wired to `logDiagnostic`. `log.warn` is still used alongside it for failures.
`DropSubmissionManager` and `PersonalBestManager` therefore have two listeners: `setListener`
(results, echoed to chat) and `setDiagnosticListener` (panel only). `RetryingCall` reports each
retry through its `onRetry` argument.

When a submission fails for good (network failure after retries, or an HTTP error), the manager
also logs the request method, URL and body via `notifyFailedRequest`. Two rules for that line:
the screenshot is replaced by a size placeholder, and headers are never logged, because they
carry the API key and the log is meant to be exported and shared.

Every HTTP error response, on all four endpoints, is also logged with an excerpt of the server's
reply. Use `ResponsePreview.of(response)` for that: it is bounded to 500 characters, flattened to
one line, and peeks the body so the caller can still read it.

**Remote error reporting is a third, separate channel.** `RemoteLogManager` sends to the logging
service, which is not the Ironkin server and is unauthenticated. It is opt-in and does *not*
receive the panel log: diagnostic text is full of player names, request bodies and chat, none of
which may leave the machine. Managers report through `RemoteLogListener` at each failure site
instead, under these rules:

- The message is a constant string. The service groups stackless events on level + exact message,
  so anything variable (IDs, HTTP status, exception text) goes in the context map.
- No RuneScape names, participants or chat text, no screenshots, and no headers (they carry the
  API key).
- A failed request is described with `RequestSummary.of(request, body)`: method, URL and the body
  fields with the image field and the name fields left out (drops send `participantCount` in place
  of the participant names). An HTTP error also sends `ResponsePreview.of(response)` as `response`.
- The item list's "empty or malformed" and parse-failure events send no response content, because
  a successful item list reply carries the event passwords.
- `logException` (which sends a stack trace) is only for exceptions thrown from this plugin's own
  code. Events with a stack are grouped by exception type and top three frames, so an
  `IOException` out of OkHttp would merge every failed request into one group; network failures
  use `log` with `exception` in the context.
- `RemoteLogManager` never reports through `DiagnosticListener`. Its own failures go to
  `log.debug` only, the one place that is correct here: a logging failure must not reach the player.

The manager enforces the endpoint limits itself (field truncation, 50 events and under 256 KB per
request, a 200-event queue that drops the oldest, repeats collapsed into an `occurrences` count)
and honours `Retry-After` on HTTP 429. `PLUGIN_VERSION` in that class must be kept in sync with
`runelite-plugin.properties` by hand.

**Server access goes through `IronkinClanApiClient`.** Do not build URLs or auth headers anywhere
else. Note the two auth headers: `x-api-key` for the events and embers endpoints,
`X-Ironkin-Plugin-Key` for Hall of Flame. Both carry the same configured API key.

### Flow: tracked item drop

1. `TrackedItemManager.fetch()` runs on startup, on login, on relevant config changes, and
   (`refresh()`) each time the panel is opened. It is guarded by an `AtomicBoolean` so it is a
   no-op once loaded; `refresh()` and `reset()` clear the guard. Every failure path must clear the
   guard too, or the list can never be fetched again.
2. The response is resolved to item names on the client thread and cached in two concurrent maps.
3. `onLootReceived` skips `PLAYER`-type loot, looks up each item, and calls
   `DropSubmissionManager.reportDrop` once per event that tracks the item.
4. For the event ID `pvm-entry` (`IronkinClanPlugin.GROUP_BOSS_EVENT_ID`) only, nearby clan members
   are attached as `participants`, and the drop is skipped for that event if no other clan member
   is nearby (`GroupComposition.hasClanBackup()`).
5. For loot from a raid chest (`RaidPartyTracker.isRaidLoot`), the clan members of the raid party
   are merged in first (`GroupComposition.withRaidClanMembers`). The chest is opened after the
   fight, when teammates have often left, so vicinity alone wrongly skipped these drops.
   `RaidPartyTracker` collects the party over the whole raid: from the party name varcs in Theatre
   of Blood and Tombs of Amascut, and from the players seen inside the dungeon in Chambers of
   Xeric, which exposes no names. Each raid has its own party, replaced only when that raid is
   entered again: leaving the raid or the party must not forget it, because unclaimed loot can be
   collected from the lobby chest afterwards.
   A `pvm-entry` drop that is still skipped is reported to the logging service as a warning
   (`reportSkippedGroupBossDrop`), with player counts and the loot source but no names.
6. `reportDrop` captures the next frame via `DrawManager`, hands off to the executor to PNG- and
   base64-encode it, then uploads through `RetryingCall`.

### Flow: personal best

1. `onInteractingChanged` feeds `CurrentBossTracker`, which records the last `BossActivity` the
   local player fought, in either interaction direction.
2. `onChatMessage` runs `PersonalBestMessageParser.parseTime`. No time means not a PB message.
3. No current boss, or the boss's `requiredMessagePhrase()` missing from the message, means a
   diagnostic and no submission. The plugin never guesses a boss name.
4. `PersonalBestManager.reportPersonalBest` screenshots and uploads as multipart form data.

`BossActivity` matches on NPC display name, not `NpcID` gamevals. This is deliberate (see the
enum's Javadoc) and is the one sanctioned exception to the "use gameval constants" rule.
`hallOfFlameName` must match the site's category name verbatim.

## Threading

| Thread | What runs there |
| --- | --- |
| Client thread | The game-event `@Subscribe` handlers (loot, chat, interacting, game state, varbit, varc string, player spawn); anything touching `Client` or `ItemManager.getItemComposition`; `ClanMemberManager.getNearbyGroupComposition()`; every `RaidPartyTracker` method |
| OkHttp pool | Every `Callback.onResponse` / `onFailure` |
| Shared `ScheduledExecutorService` | Screenshot encoding, retry backoff scheduling, the ember poll, the remote log flush |
| Swing EDT | All panel mutation; `onPanelActivated`; usually `onConfigChanged` (it fires on whichever thread changed the setting, so do not assume the client thread there) |

Rules that follow from this:

- From an OkHttp callback, use `clientThread.invoke()` before touching `client` or `itemManager`.
- Panel public methods already wrap themselves in `SwingUtilities.invokeLater`; keep that for new ones.
- The injected `ScheduledExecutorService` is RuneLite's shared one. Never shut it down. Cancel your
  own `ScheduledFuture` instead, as `EmberManager.stop()` does.
- Screenshot encoding is deliberately moved off the frame-callback thread onto the executor.
- State read across threads is `volatile`, atomic, or a concurrent collection. Keep it that way.

## HTTP conventions

- `RetryingCall.enqueue` retries twice (2s, then 4s) on network-level failures only. HTTP 4xx/5xx
  responses are terminal and are not retried.
- Use `RetryingCall` for one-shot requests whose loss matters (item list, submissions). Do not use
  it for polls; `EmberManager` calls OkHttp directly because the next poll supersedes a failure.
- Always close the response: `try (Response r = response)`.
- Wire DTOs are private static nested classes inside the manager that uses them.

## Server contract

All paths are relative to the configured server URL (a trailing slash is stripped).

- `GET /events/item-list`, header `x-api-key`.
  Response: `{"events": [{"eventId": "...", "items": [532, 4151], "eventPassword": "..."}]}`.
  `eventPassword` is optional. Names are not sent; the client resolves them.
- `POST /events/{eventId}/submissions`, header `x-api-key`, JSON body:
  `{"username", "itemid", "timestamp" (epoch ms), "imageData" (base64 PNG), "participants": []}`.
- `POST /api/hall-of-flame/plugin-submit`, header `X-Ironkin-Plugin-Key`, multipart form:
  `player`, `boss`, `time` (e.g. `1:23.40`), `proof` (PNG file part).
- `GET /api/embers/me`, header `x-api-key`. Response: `{"balance": 1234}`.

The server is a separate project. A change to any of these shapes needs a matching server change.

The logging service is a third project. Its base URL is not configurable; it is the constant
`IronkinClanApiClient.LOG_SERVICE_URL`:

- `POST /api/ingest`, no auth header, JSON body:
  `{"installId", "pluginVersion", "events": [{"level", "message", "stack"?, "context"?}]}`.
  `level` is `error`, `warn`, `info` or `debug`. `stack` must be omitted, not empty, when absent.
  202 on success; 400/413/5xx mean drop the batch; 429 carries `Retry-After`.

## Config

Group `ironkin-clan`. Keys: `serverUrl`, `apiKey`, `showDebugLog` (default off),
`showEventPasswords` (default on), `enableDropTracking` (default off),
`enablePersonalBestTracking` (default off), `enableErrorReporting` (default off).
`installId` is also stored in the group but is not in the interface: `RemoteLogManager` generates
it (a random UUID) on first send and reads it through `ConfigManager`.

- `onConfigChanged` switches on key-name string literals. Adding or renaming a key means updating
  that switch as well as the interface.
- Renames need a migration in `startUp()`. `migrateShowUploadLogKey()` is the existing example
  (`showUploadLog` became `showDebugLog`); `removeObsoleteBingoIdKey()` cleans up a removed key.
- The two submission toggles and `enableErrorReporting` carry the mandatory third-party-server
  `warning` and are opt-in.
- `enableErrorReporting` has no case in the `onConfigChanged` switch on purpose:
  `RemoteLogManager` reads it on every event and every send.

## Build, run, test

- `./gradlew test` runs the unit tests.
- `./gradlew run` launches the RuneLite dev client with the plugin loaded (see AGENTS.md, Testing).
- Java 11 target, tabs for indentation, Allman braces, Lombok only for `@Slf4j`.
- Dependencies: only `compileOnly` RuneLite client and Lombok. Do not add Gson, Guice or OkHttp.

Test conventions: mock `OkHttpClient` and `Call` and drive the captured `Callback` by hand; mock the
executor so `schedule(...)` runs the retry synchronously; call the package-private `upload*`
methods directly instead of driving `DrawManager`. There are no tests for `IronkinClanPlugin`, the
panel, the overlay, or `EmberManager`, so logic worth testing belongs in a manager or a pure helper
(as `PersonalBestMessageParser` and `GroupComposition` are).

## Constraints specific to this plugin

- **PvP loot is never reported.** `LootRecordType.PLAYER` is dropped on purpose; reporting it would
  crowdsource another player's gear. Do not "fix" this.
- **Participants are limited to members of the user's own clan channel**, and only for `pvm-entry`.
  Do not widen this to arbitrary nearby players. `RaidPartyTracker` holds the other raid party
  names in memory to check them against the clan channel, but only clan members are ever sent.
- **Loot detection depends on the built-in Loot Tracker plugin being enabled.**
- **Nothing is written to disk.** All state is in memory and rebuilt from the server. The one
  persisted value is the `installId` config entry.
- **The overlay stays registered** and renders nothing when empty, instead of being added and removed.

## Known rough edges

- A comment in `DropSubmissionManager` refers to `GroupBossRegistry`, which no longer exists. The
  logic it describes lives in `IronkinClanPlugin.handleLootReceived`.
- The four-argument `DropSubmissionManager.reportDrop` overload has no callers in main code.
- Ember polling has no toggle of its own: it runs whenever a server URL and API key are set.
