package com.mobstrategy;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("mob-strategy")
public interface MobStrategyConfig extends Config
{
	@ConfigItem(
		keyName = "liveWikiLookups",
		name = "Live wiki lookups",
		description = "Get the latest gear and strategy info straight from the OSRS Wiki, instead of only the info built into the plugin",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"
	)
	default boolean liveWikiLookups()
	{
		return false;
	}
}
