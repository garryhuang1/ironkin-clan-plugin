# Ironkin Clan

A RuneLite plugin for the Ironkin clan. It connects your client to the clan's server to report tracked event drops, submit personal bests to the Hall of Flame, and show your ember balance and event passwords in game.

## Features

- **Tracked item drops.** Fetches the item list for every event active for your API key. When one of those items drops, the plugin screenshots the client and reports the drop to each event that tracks it.
- **Group boss credit.** For the `pvm-entry` event, clan members standing nearby are credited alongside you. At least one other clan member must be nearby, so solo kills never count for this event.
- **Personal bests.** When the game announces a new personal best at a supported boss, the plugin screenshots it and submits it to the Hall of Flame.
- **Ember balance.** Shows your ember balance in the side panel and posts a chat message when you are awarded more.
- **Event passwords.** An in-game overlay lists the password for any active event that has one.
- **Side panel.** Ember balance, tracked items grouped by event (collapsible, with item icons), and an optional log that can be copied to the clipboard.

## Setup

Open the plugin's configuration in RuneLite:

| Setting | Default | Description |
| --- | --- | --- |
| Server URL | empty | Base URL of the clan server, e.g. `https://ironkin.example.com` |
| API Key | empty | Your key for the clan server. It also determines which events are active for you, so there is no event to select |
| Show log | off | Shows the log section in the side panel |
| Show event passwords | on | Shows the event password overlay |
| Enable drop tracking | off | Fetches the tracked item list and uploads matching drops, with a screenshot |
| Enable personal best tracking | off | Uploads new personal bests at supported bosses, with a screenshot |

Nothing is sent anywhere until both the server URL and API key are set. Drop tracking and personal best tracking are each opt-in on top of that. The ember balance is fetched whenever the URL and key are set.

## Usage

1. Fill in the server URL and API key, then enable the features you want.
2. Open the **Ironkin Clan** panel from the sidebar. Opening it refreshes the tracked item list and your ember balance, so server-side changes such as a new event show up without relogging.
3. Play as normal. Each drop or personal best submission is reported in game chat as a success or failure.

If something did not submit and you expected it to, turn on **Show log**. The log explains skipped submissions (for example, no clan member nearby for `pvm-entry`, or a personal best at a boss that could not be identified), and **Export** copies it to your clipboard for sharing.

### Supported personal best bosses

Shellbane Gryphon, Mad Angel, Maggot King, Doom of Mokhaiotl (Floors 1-8), Araxxor, Amoxliatl, Colosseum, Vardorvis, Whisperer, Leviathan, Duke Sucellus, Phantom Muspah, Phosani's Nightmare, Corrupted Gauntlet, Gauntlet, Alchemical Hydra, Hespori, Vorkath, Grotesque Guardians, Inferno, Zulrah, Fight Caves.

A personal best anywhere else is ignored.

## Known limitations

- **Loot Tracker must be enabled.** Drops are detected through RuneLite's built-in Loot Tracker plugin (on by default). If you disable it, this plugin sees no drops.
- **PvP loot is not tracked, by design.** Loot from another player comes from that player's gear, and reporting it would mean collecting data about other players, which the RuneLite plugin hub does not allow.
- **Personal bests rely on the chat message.** The boss is taken from whichever supported boss you last fought, so the "new personal best" game message must appear for a submission to happen.

## Privacy

The plugin only contacts the server URL you configure. Depending on which features you enable, it sends your character name, the dropped item ID or boss and time, a screenshot of your client at that moment, and, for `pvm-entry` drops only, the names of nearby members of your own clan. Data about players outside your clan is never sent.

## Development

Requires JDK 11 or newer.

```
./gradlew test    # run the unit tests
./gradlew run     # launch a RuneLite development client with the plugin loaded
```

To log in to the development client, follow RuneLite's [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) guide.

### Architecture

```
com.ironkinclan
├── IronkinClanPlugin   lifecycle, event subscribers, orchestration
├── config/             the settings interface
├── api/                request building (URLs, auth headers) and retry with backoff
├── manager/            one class per responsibility: item list, drop upload,
│                       personal bests, embers, nearby clan members
├── model/              plain data types and the supported boss list
└── ui/                 side panel and event password overlay
```

`IronkinClanPlugin` is the only class that subscribes to RuneLite events and the only one that knows about every feature. Managers do not reference each other or the UI. They receive their dependencies through constructor injection and report results through small listener interfaces that the plugin wires up on start and clears on shutdown.

All server access goes through `IronkinClanApiClient`, so URLs and authentication live in one place. Requests are always asynchronous on OkHttp's thread pool. One-shot requests retry twice with exponential backoff on network failures; HTTP error responses are not retried.

[CONTEXT.md](CONTEXT.md) covers the data flows, threading rules and conventions in more depth.

### Practices this project follows

- **Single responsibility.** Each manager does one job, and logic that needs no game state is kept in pure classes (`PersonalBestMessageParser`, `GroupComposition`, `BossActivity`) so it can be tested directly.
- **Dependency injection over construction.** Collaborators are injected, never created inline, which is what lets the tests substitute mocks for the HTTP client and executor.
- **Never block the client thread.** Network calls and screenshot encoding happen off it. Game state is only read on it, and the panel is only changed on the Swing thread.
- **Fail visibly.** Every failure path is reported to the panel log; submission results also go to game chat. Nothing is dropped silently.
- **Opt-in data sharing.** Features that upload are off by default and carry RuneLite's third-party server warning.
- **Backwards-compatible settings.** A renamed setting ships with a migration so users keep their saved value.
- **Clean shutdown.** Listeners, the overlay, the sidebar button and the scheduled poll are all released when the plugin stops.
- **Tests alongside features.** New manager or parsing logic comes with unit tests (JUnit 4 and Mockito) under the matching package in `src/test`.
- **Focused commits.** Formatting changes are kept separate from behavior changes.

Contributions must also follow the RuneLite plugin hub rules summarized in [AGENTS.md](AGENTS.md).

## Server contract

All paths are relative to the configured server URL.

### `GET /events/item-list`

Header `x-api-key: <key>`. Returns every event active for that key.

```json
{
  "events": [
    { "eventId": "bounty-123", "items": [532, 4151], "eventPassword": "flame42" },
    { "eventId": "pvm-entry", "items": [995] }
  ]
}
```

`items` is a flat array of item IDs; the plugin resolves names from RuneLite's item cache. `eventPassword` is optional. If an item appears under several events, a matching drop is reported to each.

### `POST /events/{eventId}/submissions`

Header `x-api-key: <key>`. JSON body:

```json
{
  "username": "PlayerName",
  "itemid": 532,
  "timestamp": 1720280000000,
  "imageData": "<base64 PNG>",
  "participants": ["Clanmate1", "Clanmate2"]
}
```

`participants` is populated only for the `pvm-entry` event and is an empty array otherwise.

### `POST /api/hall-of-flame/plugin-submit`

Header `X-Ironkin-Plugin-Key: <key>` (the same API key, under a different header name). Multipart form body:

| Part | Value |
| --- | --- |
| `player` | Character name |
| `boss` | Hall of Flame category name, exactly as listed under supported bosses |
| `time` | Completion time, e.g. `1:23.40` or `0:35` |
| `proof` | PNG screenshot |

### `GET /api/embers/me`

Header `x-api-key: <key>`. Polled every 5 minutes while logged in.

```json
{ "balance": 1234 }
```

## License

BSD 2-Clause. See [LICENSE](LICENSE).
