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

    @Range(min = 0, max = 10)
    @ConfigItem(
          keyName = "nearbyAreaRange",
          name = "Nearby areas",
          description = "Also outline separate walkable areas that start within this many tiles of the area you can reach"
              + " (the other side of a fence, wall or river). 0 = off",
          section = wallsSection,
          position = 3
    )
    default int nearbyAreaRange()
    {
        return 0;
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

    @ConfigItem(
          keyName = "shortcutLines",
          name = "Shortcut lines",
          description = "Draw a dashed line from each shortcut to where it leads",
          section = passageSection,
          position = 2
    )
    default boolean shortcutLines()
    {
        return false;
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

    enum BoxStyle
    {
        /** Outline of the object's model. Needs a 3D model projection per object per frame. */
        CLICKBOX,
        /** Outline of the tiles the object stands on. Much cheaper. */
        TILES,
        /** Labels only. */
        NONE
    }

    @ConfigItem(
          keyName = "boxStyle",
          name = "Outline",
          description = "Click box: outline the object's model. Tiles: outline the tiles it stands on (much cheaper). None: labels only",
          section = objectSection,
          position = 1
    )
    default BoxStyle boxStyle()
    {
        return BoxStyle.CLICKBOX;
    }

    @ConfigItem(
          keyName = "objectLabels",
          name = "Labels",
          description = "Write the object's name and action above it",
          section = objectSection,
          position = 2
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
          position = 3
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
          position = 4
    )
    default String actionFilter()
    {
        return "Open, Close, Climb, Enter, Exit, Pass, Squeeze, Cross, Jump, Walk-across, Go-through, Crawl, Balance, Swing, Leave";
    }
}
