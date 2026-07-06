package com.ironkinclan;

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
		return "http://localhost:3000";
	}

	@ConfigItem(
		keyName = "bingoId",
		name = "Bingo ID",
		description = "ID of the bingo whose tracked item list and drops should be reported"
	)
	default String bingoId()
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
		keyName = "showUploadLog",
		name = "Show upload log",
		description = "Show a log of drop uploads in the plugin panel"
	)
	default boolean showUploadLog()
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
