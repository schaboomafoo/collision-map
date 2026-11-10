package com.example;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
		name = "Collision Map"
)
public class CollisionMapPlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private CollisionMapOverlay collisionMapOverlay;

	@Override
	protected void startUp() throws Exception
	{
		overlayManager.add(collisionMapOverlay);
		log.info("Collision Map started!");
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(collisionMapOverlay);
		log.info("Collision Map stopped!");
	}

	@Provides
	CollisionMapConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CollisionMapConfig.class);
	}
}