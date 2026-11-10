package com.example;

import java.awt.Color;

import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("collisionmap")
public interface CollisionMapConfig extends Config
{
	@ConfigItem(
			keyName = "showBlockedTiles",
			name = "Show blocked tiles",
			description = "Highlight tiles that cannot be walked on"
	)
	default boolean showBlockedTiles()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showWalls",
			name = "Show walls",
			description = "Draw lines along cardinal movement boundaries (doesn't really work on its own since some walls adjacent to unwalkable tiles are ignored)"
	)
	default boolean showWalls()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
			keyName = "tileColor",
			name = "Tile color",
			description = "Color and transparency of blocked tiles"
	)
	default Color tileColor()
	{
		return new Color(255, 100, 0, 100);
	}

	@Alpha
	@ConfigItem(
			keyName = "wallColor",
			name = "Wall color",
			description = "Color and transparency of wall lines"
	)
	default Color wallColor()
	{
		return new Color(255, 100, 0, 100);
	}

	@Range(
			min = 1,
			max = 8
	)
	@ConfigItem(
			keyName = "wallThickness",
			name = "Wall thickness",
			description = "Thickness of collision wall lines"
	)
	default int wallThickness()
	{
		return 2;
	}
}
