package com.collisionmap;

import java.awt.Color;

import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("collisionmap")
public interface CollisionMapConfig extends Config
{
    @ConfigSection(
          name = "Walls",
          description = "The outline of the area you can walk to",
          position = 0
    )
    String wallsSection = "walls";

    @ConfigSection(
          name = "Doors & shortcuts",
          description = "Ways out of the area you are in",
          position = 1
    )
    String passageSection = "passages";

    @ConfigSection(
          name = "Objects",
          description = "Labels and click boxes for interactable objects",
          position = 2
    )
    String objectSection = "objects";

    // ------------------------------------------------------------------ walls

    @Range(min = 3, max = 52)
    @ConfigItem(
          keyName = "drawRadius",
          name = "Draw radius",
          description = "Only draw within this many tiles of you",
          section = wallsSection,
          position = 0
    )
    default int drawRadius()
    {
        return 20;
    }

    @Alpha
    @ConfigItem(
          keyName = "wallColor",
          name = "Wall color",
          description = "Color of the walls around the area you can reach",
          section = wallsSection,
          position = 1
    )
    default Color wallColor()
    {
        return new Color(255, 165, 0, 220);
    }

    @Range(min = 1, max = 8)
    @ConfigItem(
          keyName = "wallThickness",
          name = "Wall thickness",
          description = "Thickness of wall lines",
          section = wallsSection,
          position = 2
    )
    default int wallThickness()
    {
        return 2;
    }

    // ------------------------------------------------------------------ doors & shortcuts

    @Range(min = 0, max = 3)
    @ConfigItem(
          keyName = "doorsToFollow",
          name = "Look through doors",
          description = "How many doors/shortcuts deep to show. 0 = only your area (doors are still marked), 1 = also the area behind each door, and so on",
          section = passageSection,
          position = 0
    )
    default int doorsToFollow()
    {
        return 1;
    }

    @Alpha
    @ConfigItem(
          keyName = "passageColor",
          name = "Door color",
          description = "Color of doors, gates and shortcuts that lead out of your area",
          section = passageSection,
          position = 1
    )
    default Color passageColor()
    {
        return new Color(0, 255, 100, 230);
    }

    // ------------------------------------------------------------------ objects

    @ConfigItem(
          keyName = "showObjects",
          name = "Mark objects",
          description = "Outline the click box of doors, gates, shortcuts and similar objects",
          section = objectSection,
          position = 0
    )
    default boolean showObjects()
    {
        return false;
    }

    @ConfigItem(
          keyName = "objectLabels",
          name = "Labels",
          description = "Write the object's name and action above it",
          section = objectSection,
          position = 1
    )
    default boolean objectLabels()
    {
        return true;
    }

    @Alpha
    @ConfigItem(
          keyName = "objectColor",
          name = "Object color",
          description = "Color of click boxes and labels",
          section = objectSection,
          position = 2
    )
    default Color objectColor()
    {
        return new Color(255, 235, 59, 230);
    }

    @ConfigItem(
          keyName = "actionFilter",
          name = "Actions to mark",
          description = "Comma separated. An object is marked if any of its actions starts with one of these",
          section = objectSection,
          position = 3
    )
    default String actionFilter()
    {
        return "Open, Close, Climb, Enter, Exit, Pass, Squeeze, Cross, Jump, Walk-across, Go-through, Crawl, Balance, Swing, Leave";
    }
}
