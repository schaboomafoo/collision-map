package com.collisionmap;

import java.util.Arrays;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.api.ObjectComposition;

/**
 * Answers "is there a door or gate here?" for {@link Reachability}, from live objects in the scene
 * with an "Open" action. Used to colour those edges, not to see past them.
 */
@Singleton
public class PassageIndex implements Reachability.Passages
{
    /**
     * A game object with an "Open" action only counts as a passage if its name looks like one.
     * Otherwise chests, cupboards and wardrobes would look like gates.
     */
    private static final Pattern GATE_NAME = Pattern.compile(
        "(?i)\\b(door|gate|doorway|barrier|portcullis|curtain|grill|grille|turnstile|stile|fence)s?\\b");

    private final ObjectTracker tracker;

    private byte[][] openEdges = new byte[0][0];
    private boolean[][] openTiles = new boolean[0][0];

    @Inject
    PassageIndex(ObjectTracker tracker)
    {
        this.tracker = tracker;
    }

    /** Rebuilds the door/gate grids for the current scene and plane. */
    public void rebuild(int width, int height, int plane)
    {
        if (openEdges.length != width || (width > 0 && openEdges[0].length != height))
        {
            openEdges = new byte[width][height];
            openTiles = new boolean[width][height];
        }
        else
        {
            for (int x = 0; x < width; x++)
            {
                Arrays.fill(openEdges[x], (byte) 0);
                Arrays.fill(openTiles[x], false);
            }
        }

        for (ObjectTracker.Tracked t : tracker.all())
        {
            if (t.plane != plane || (t.kind != ObjectTracker.Kind.WALL && t.kind != ObjectTracker.Kind.GAME))
            {
                continue;
            }
            ObjectComposition comp = t.current();
            if (comp == null || !hasOpenAction(comp.getActions()))
            {
                continue;
            }

            if (t.kind == ObjectTracker.Kind.WALL)
            {
                // orientation bit 1 = W, 2 = N, 4 = E, 8 = S. Diagonal wall pieces (16+) are ignored.
                if (inBounds(t.sceneX, t.sceneY, width, height))
                {
                    int o = t.wallOrientation;
                    if ((o & 1) != 0) openEdges[t.sceneX][t.sceneY] |= 1 << Reachability.W;
                    if ((o & 2) != 0) openEdges[t.sceneX][t.sceneY] |= 1 << Reachability.N;
                    if ((o & 4) != 0) openEdges[t.sceneX][t.sceneY] |= 1 << Reachability.E;
                    if ((o & 8) != 0) openEdges[t.sceneX][t.sceneY] |= 1 << Reachability.S;
                }
            }
            else if (comp.getName() != null && GATE_NAME.matcher(comp.getName()).find())
            {
                for (int x = t.sceneX; x < t.sceneX + t.sizeX; x++)
                {
                    for (int y = t.sceneY; y < t.sceneY + t.sizeY; y++)
                    {
                        if (inBounds(x, y, width, height))
                        {
                            openTiles[x][y] = true;
                        }
                    }
                }
            }
        }
    }

    private static boolean inBounds(int x, int y, int w, int h)
    {
        return x >= 0 && y >= 0 && x < w && y < h;
    }

    /** "Open" only. A "Close" action means the door is already open, and open doors have no wall flag. */
    static boolean hasOpenAction(String[] actions)
    {
        if (actions != null)
        {
            for (String a : actions)
            {
                if (a != null && a.regionMatches(true, 0, "Open", 0, 4))
                {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean canOpenEdge(int x, int y, int dir)
    {
        if (openEdges.length == 0)
        {
            return false;
        }
        int w = openEdges.length, h = openEdges[0].length;
        if (inBounds(x, y, w, h) && (openEdges[x][y] >> dir & 1) != 0)
        {
            return true;
        }
        int nx = x + Reachability.DX[dir], ny = y + Reachability.DY[dir];
        return inBounds(nx, ny, w, h) && (openEdges[nx][ny] >> Reachability.opposite(dir) & 1) != 0;
    }

    @Override
    public boolean canOpenTile(int x, int y)
    {
        return openTiles.length > 0 && inBounds(x, y, openTiles.length, openTiles[0].length) && openTiles[x][y];
    }
}
