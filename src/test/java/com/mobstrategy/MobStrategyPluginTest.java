package com.mobstrategy;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class MobStrategyPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(MobStrategyPlugin.class);
		RuneLite.main(args);
	}
}