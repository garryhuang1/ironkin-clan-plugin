package com.ironkinclan.config;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(IronkinClanConfig.CONFIG_GROUP)
public interface IronkinClanConfig extends Config
{
	String CONFIG_GROUP = "ironkin-clan";

	@ConfigItem(
		keyName = "serverUrl",
		name = "Server URL",
		description = "Base URL of the Ironkin server, e.g. https://ironkin.example.com"
	)
	default String serverUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "apiKey",
		name = "API Key",
		description = "API key used to authenticate with the Ironkin server"
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "showDebugLog",
		name = "Show log",
		description = "Show a log of drop uploads and diagnostic warnings (e.g. failed item list fetches) in the plugin panel"
	)
	default boolean showDebugLog()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showEventPasswords",
		name = "Show event passwords",
		description = "Shows an overlay with the password(s) for any tracked events that have one configured"
	)
	default boolean showEventPasswords()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableDropTracking",
		name = "Enable drop tracking",
		description = "Screenshots item drops that match the tracked item list and uploads them to the Ironkin server",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"
	)
	default boolean enableDropTracking()
	{
		return false;
	}
}
