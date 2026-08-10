# Ironkin Clan

A RuneLite plugin for the Ironkin clan that tracks bingo item drops. When a tracked item drops, the plugin screenshots the client and reports the drop to the clan's server.

## Features

- Fetches every active event's tracked item list from the clan server (keyed off your API key — no manual event selection needed) and caches it in memory
- Detects loot matching a tracked item ID from monster kills, clue scroll rewards, raid/Barrows chests, and most minigame rewards — via the built-in Loot Tracker plugin's `LootReceived` broadcast, rather than reimplementing detection for each source
- Screenshots the client window and uploads the drop (player name, item ID, timestamp, screenshot) to the server, once per event that tracks the dropped item
- For drops reported to the `pvm-entry` event (group boss PvM entry-fee tracking), also includes nearby clan members (from RuneLite's in-game Clan) as `participants` on the submission, so credit isn't limited to whoever received the loot roll. The clan must be a strict majority of the nearby group (e.g. 2/2, 2/3, 3/4, 3/5, 4/6, 4/7) or the drop is skipped for this event entirely - solo kills never qualify
- Sidebar panel showing tracked items grouped by event (with icons) and a log of upload attempts
- In-game chat message on every upload attempt, success or failure

## Setup

Open the plugin's configuration panel in RuneLite and fill in:

| Setting | Description |
| --- | --- |
| Server URL | Base URL of the clan server, e.g. `https://ironkin.example.com` |
| API Key | API key used to authenticate with the server (sent as the `x-api-key` header). The server uses this to determine which event(s) are active — no separate event ID setting is needed |
| Show log | Toggles the log section (uploads and diagnostic warnings) in the plugin panel (on by default) |
| Enable drop tracking | Turns the feature on. **Off by default** — enabling it lets the plugin send data (including screenshots) to the configured server |

Drop tracking won't fetch anything or do anything until the server URL and API key are both set, and the feature is enabled.

## Usage

1. Configure the settings above and enable drop tracking.
2. Open the plugin panel from the sidebar — this fetches (or refreshes) the tracked item list, shown grouped by event, each as its own grid of item icons. Reopening the panel always re-fetches the list, so changes made on the server side (e.g. a new event) show up without needing to log out.
3. When you receive a tracked item as loot, the plugin automatically screenshots the client and uploads the drop to every event that tracks that item. Successes and failures are both reported as a game chat message and, if enabled, a line in the panel's log.

## Server contract

- `GET {Server URL}/events/item-list` — header `x-api-key: <key>`. Returns every event active for the given API key. Expected response:
  ```json
  {
    "events": [
      { "eventId": "bounty-123", "items": [532, 4151] },
      { "eventId": "botw-123", "items": [995] }
    ]
  }
  ```
  `items` is a flat array of item IDs — the plugin resolves each item's name from RuneLite's own item cache, so the server doesn't need to send one. If the same item ID appears under more than one event, a matching drop is reported to each of those events.

- `POST {Server URL}/events/{eventId}/submissions` — header `x-api-key: <key>`. Body:
  ```json
  {
    "username": "PlayerName",
    "itemid": 532,
    "timestamp": 1720280000000,
    "imageData": "<base64 PNG>",
    "participants": ["Clanmate1", "Clanmate2"]
  }
  ```
  `participants` lists other nearby clan members (from RuneLite's in-game Clan) to credit alongside `username`. It's only populated for drops reported to the `pvm-entry` event ID (hardcoded in `IronkinClanPlugin.GROUP_BOSS_EVENT_ID`) — an empty array for every other event.

## Known Issues

- Loot detection depends on RuneLite's built-in **Loot Tracker** plugin being enabled (it is by default). If a clan member disables it, our plugin will stop seeing any drops at all, since Loot Tracker is what actually detects each loot source and broadcasts the result we listen for.

## Out of scope

- **PvP loot is intentionally not tracked.** Unlike monster drops, loot from killing another player is derived from that player's own gear — reporting it to a third-party server amounts to crowdsourcing another player's equipment/data, which RuneLite's plugin hub explicitly rejects plugins for doing. This isn't a gap to be fixed; it should stay out of scope.
