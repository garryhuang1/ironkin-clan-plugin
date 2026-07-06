# Ironkin Clan
A plugin for the Ironkin clan

## Known Issues

- Drop tracking only detects monster kill loot (`NpcLootReceived`). Clue scroll rewards and raid chest loot (CoX/ToB/ToA) are not currently detected — RuneLite doesn't expose those as a simple event, they'd need widget-load + inventory-diff tracking similar to the built-in Loot Tracker plugin.