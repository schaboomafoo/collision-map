package com.collisionmap;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;

/**
 * Keeps the scene's interactable objects in a flat map so nothing has to walk the scene per frame.
 *
 * <p>Fed by the spawn/despawn/change events in {@link CollisionMapPlugin}, all of which arrive on
 * the client thread, as do the overlays that read this, so no locking is needed.
 *
 * <p>An object is kept if it has at least one action, or if it is an impostor (its actions depend on
 * a varbit, e.g. a shortcut that is only usable after a quest). Which of those count as
 * "interesting" is decided later, at draw time, so config changes apply instantly.
 */
@Singleton
public class ObjectTracker
{
    public enum Kind
    {
        WALL, GAME, DECORATIVE, GROUND
    }

    public static final class Tracked
    {
        public final TileObject object;
        public final Kind kind;
        public final int plane;
        /** Scene coordinates of the south-west tile of the object's footprint. */
        public final int sceneX, sceneY;
        public final int sizeX, sizeY;
        /** Wall objects only: orientation bitmask (1 = W, 2 = N, 4 = E, 8 = S). */
        public final int wallOrientation;
        private final ObjectComposition base;

        Tracked(TileObject object, Kind kind, int sceneX, int sceneY, int sizeX, int sizeY,
                int wallOrientation, ObjectComposition base)
        {
            this.object = object;
            this.kind = kind;
            this.plane = object.getPlane();
            this.sceneX = sceneX;
            this.sceneY = sceneY;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.wallOrientation = wallOrientation;
            this.base = base;
        }

        /** The composition currently in effect, following the impostor if there is one. May be null. */
        public ObjectComposition current()
        {
            return base.getImpostorIds() != null ? base.getImpostor() : base;
        }

        /** Chebyshev distance in tiles from a scene tile to the nearest tile of the footprint. */
        public int distanceTo(int x, int y)
        {
            int dx = x < sceneX ? sceneX - x : Math.max(0, x - (sceneX + sizeX - 1));
            int dy = y < sceneY ? sceneY - y : Math.max(0, y - (sceneY + sizeY - 1));
            return Math.max(dx, dy);
        }
    }

    private final Client client;
    private final Map<TileObject, Tracked> tracked = new HashMap<>();
    private int version;

    @Inject
    ObjectTracker(Client client)
    {
        this.client = client;
    }

    public Collection<Tracked> all()
    {
        return tracked.values();
    }

    /** Bumps whenever the set of tracked objects changes. */
    public int version()
    {
        return version;
    }

    public void clear()
    {
        if (!tracked.isEmpty())
        {
            tracked.clear();
            version++;
        }
    }

    public void add(GameObject o)
    {
        Point min = o.getSceneMinLocation();
        track(o, Kind.GAME, min.getX(), min.getY(), Math.max(1, o.sizeX()), Math.max(1, o.sizeY()), 0);
    }

    public void add(WallObject o)
    {
        LocalPoint lp = o.getLocalLocation();
        track(o, Kind.WALL, lp.getSceneX(), lp.getSceneY(), 1, 1, o.getOrientationA());
    }

    public void add(DecorativeObject o)
    {
        LocalPoint lp = o.getLocalLocation();
        track(o, Kind.DECORATIVE, lp.getSceneX(), lp.getSceneY(), 1, 1, 0);
    }

    public void add(GroundObject o)
    {
        LocalPoint lp = o.getLocalLocation();
        track(o, Kind.GROUND, lp.getSceneX(), lp.getSceneY(), 1, 1, 0);
    }

    public void remove(TileObject o)
    {
        if (o != null && tracked.remove(o) != null)
        {
            version++;
        }
    }

    private void track(TileObject o, Kind kind, int sx, int sy, int w, int h, int wallOrientation)
    {
        ObjectComposition comp = client.getObjectDefinition(o.getId());
        if (comp == null)
        {
            return;
        }
        if (comp.getImpostorIds() == null && !hasAnyAction(comp.getActions()))
        {
            return;
        }
        tracked.put(o, new Tracked(o, kind, sx, sy, w, h, wallOrientation, comp));
        version++;
    }

    static boolean hasAnyAction(String[] actions)
    {
        if (actions != null)
        {
            for (String a : actions)
            {
                if (a != null && !a.isEmpty())
                {
                    return true;
                }
            }
        }
        return false;
    }
}
