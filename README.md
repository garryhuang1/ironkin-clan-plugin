# Ironkin Clan

A RuneLite plugin for the Ironkin clan that tracks bingo item drops. When a tracked item drops from a monster kill, the plugin screenshots the client and reports the drop to the clan's server.

## Features

- Fetches a bingo's tracked item list from the clan server and caches it in memory
- Detects monster kill loot matching a tracked item ID
- Screenshots the client window and uploads the drop (player name, item ID, timestamp, screenshot) to the server
- Sidebar panel showing the current tracked items (with icons) and a log of upload attempts
- In-game chat message on every upload attempt, success or failure

## Setup

Open the plugin's configuration panel in RuneLite and fill in:

| Setting | Description |
| --- | --- |
| Server URL | Base URL of the clan server, e.g. `https://ironkin.example.com` |
| Bingo ID | ID of the bingo whose item list/drops apply. Both the item list and drop uploads are sent to `{Server URL}/bingos/{Bingo ID}` |
| API Key | API key used to authenticate with the server (sent as the `x-api-key` header) |
| Show upload log | Toggles the upload log section in the plugin panel (on by default) |
| Enable drop tracking | Turns the feature on. **Off by default** — enabling it lets the plugin send data (including screenshots) to the configured server |

Drop tracking won't fetch anything or do anything until the server URL, API key, and bingo ID are all set, and the feature is enabled.

## Usage

1. Configure the settings above and enable drop tracking.
2. Open the plugin panel from the sidebar — this fetches (or refreshes) the tracked item list, shown as a grid of item icons. Reopening the panel always re-fetches the list, so changes made on the server side (e.g. a new bingo board) show up without needing to log out.
3. When you kill a monster that drops a tracked item, the plugin automatically screenshots the client and uploads the drop. Successes and failures are both reported as a game chat message and, if enabled, a line in the panel's upload log.

## Server contract

- `GET {Server URL}/bingos/{Bingo ID}` — header `x-api-key: <key>`. Expected response:
  ```json
  {
    "bingoId": "abc123",
    "items": [
      { "id": 532 }
    ]
  }
  ```
  Only `id` is used — the plugin resolves the item's name from RuneLite's own item cache, so the server doesn't need to send one.

- `POST {Server URL}/bingos/{Bingo ID}` — header `x-api-key: <key>`. Body:
  ```json
  {
    "username": "PlayerName",
    "itemid": 532,
    "timestamp": 1720280000000,
    "imageData": "<base64 PNG>"
  }
  ```

## Known Issues

- Drop tracking only detects monster kill loot (`NpcLootReceived`). Clue scroll rewards and raid chest loot (CoX/ToB/ToA) are not currently detected — RuneLite doesn't expose those as a simple event, they'd need widget-load + inventory-diff tracking similar to the built-in Loot Tracker plugin.
