package com.mobstrategy;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Mob Strategy",
	description = "Shows recommended gear and strategies for monsters from the OSRS Wiki",
	tags = {"slayer", "boss", "gear", "strategy", "wiki"}
)
public class MobStrategyPlugin extends Plugin
{
	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private MobStrategyConfig config;

	private MobStrategyPanel panel;
	private NavigationButton navButton;

	@Override
	protected void startUp() throws Exception
	{
		// Build the side panel and add its button to RuneLite's sidebar
		panel = new MobStrategyPanel();

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");

		navButton = NavigationButton.builder()
			.tooltip("Mob Strategy")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
		log.debug("Mob Strategy started!");
	}

	@Override
	protected void shutDown() throws Exception
	{
		// Remove the sidebar button when the plugin is turned off
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
		log.debug("Mob Strategy stopped!");
	}

	@Provides
	MobStrategyConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MobStrategyConfig.class);
	}
}
