package com.collisionmap;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/** Where the player is on the top-level (main) map, including while standing on a boat. */
final class PlayerPosition
{
    private PlayerPosition()
    {
    }

    /**
     * The player's location in the top-level world view, or null. On a boat (sailing) the player's
     * own location is relative to the boat's small world view, so it is mapped through the boat's
     * {@link WorldEntity} onto the main map; otherwise flood fills and the draw radius would be
     * centred on the wrong tile.
     */
    static LocalPoint onTopLevel(Client client)
    {
        Player player = client.getLocalPlayer();
        WorldView top = client.getTopLevelWorldView();
        if (player == null || top == null)
        {
            return null;
        }
        LocalPoint lp = player.getLocalLocation();
        WorldView own = player.getWorldView();
        if (lp == null || own == null || own.isTopLevel())
        {
            return lp;
        }
        for (WorldEntity entity : top.worldEntities())
        {
            if (entity.getWorldView() == own)
            {
                return entity.transformToMainWorld(lp);
            }
        }
        return null;
    }
}
