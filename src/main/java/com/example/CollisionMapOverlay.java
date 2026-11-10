package com.example;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;

import javax.inject.Inject;

import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

public class CollisionMapOverlay extends Overlay
{

    private final Client client;
    private final CollisionMapConfig config;

    @Inject
    private CollisionMapOverlay(Client client, CollisionMapConfig config)
    {
        this.client = client;
        this.config = config;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        WorldView worldView = client.getTopLevelWorldView();

        if (worldView == null)
        {
            return null;
        }

        CollisionData[] collisionMaps = worldView.getCollisionMaps();

        if (collisionMaps == null)
        {
            return null;
        }

        int plane = worldView.getPlane();

        if (plane < 0 || plane >= collisionMaps.length
                || collisionMaps[plane] == null)
        {
            return null;
        }

        int[][] flags = collisionMaps[plane].getFlags();

        graphics.setStroke(new BasicStroke(
                config.wallThickness(),
                BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER
        ));

        for (int x = 0; x < flags.length; x++)
        {
            for (int y = 0; y < flags[x].length; y++)
            {
                int flag = flags[x][y];

                // Ignore tiles outside the usable scene
                if (flag == 0xFFFFFF)
                {
                    continue;
                }

                LocalPoint localPoint =
                        LocalPoint.fromScene(x, y, worldView);

                Polygon polygon =
                        Perspective.getCanvasTilePoly(client, localPoint);

                if (polygon == null)
                {
                    continue;
                }

                // Completely blocked tiles
                if (config.showBlockedTiles()
                        && (flag & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0)
                {
                    graphics.setColor(config.tileColor());
                    graphics.fillPolygon(polygon);
                }

                // Cardinal walls
                if (config.showWalls()
                        && (flag & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0)
                {
                    graphics.setColor(config.wallColor());

                    // North
                    if ((flag & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0
                            && y + 1 < flags[x].length
                            && (flags[x][y + 1] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0)
                    {
                        graphics.drawLine(
                                polygon.xpoints[2], polygon.ypoints[2],
                                polygon.xpoints[3], polygon.ypoints[3]
                        );
                    }

                    // East
                    if ((flag & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0
                            && x + 1 < flags.length
                            && (flags[x + 1][y] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0)
                    {
                        graphics.drawLine(
                                polygon.xpoints[1], polygon.ypoints[1],
                                polygon.xpoints[2], polygon.ypoints[2]
                        );
                    }

                    // South
                    if ((flag & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0
                            && y - 1 >= 0
                            && (flags[x][y - 1] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0)
                    {
                        graphics.drawLine(
                                polygon.xpoints[0], polygon.ypoints[0],
                                polygon.xpoints[1], polygon.ypoints[1]
                        );
                    }

                    // West
                    if ((flag & CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0
                            && x - 1 >= 0
                            && (flags[x - 1][y] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0)
                    {
                        graphics.drawLine(
                                polygon.xpoints[3], polygon.ypoints[3],
                                polygon.xpoints[0], polygon.ypoints[0]
                        );
                    }
                }
            }
        }

        return null;
    }
}