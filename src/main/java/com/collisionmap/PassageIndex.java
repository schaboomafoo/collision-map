package com.collisionmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.api.Client;
import net.runelite.api.ObjectComposition;

/**
 * Answers "where can a door, gate or shortcut take me from here?" for {@link Reachability}.
 *
 * <p>Two sources:
 * <ul>
 *   <li>the bundled transport table (exact: origin tile, destination tile, object id), and</li>
 *   <li>live objects in the scene with an "Open" action (heuristic: it catches doors the table doesn't
 *       know about, but can't say where non-adjacent shortcuts lead).</li>
 * </ul>
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

    private final Client client;
    private final ObjectTracker tracker;
    private final TransportData transportData;

    private final Map<Integer, int[]> dataFrom = new HashMap<>();
    private byte[][] openEdges = new byte[0][0];
    private boolean[][] openTiles = new boolean[0][0];

    @Inject
    PassageIndex(Client client, ObjectTracker tracker, TransportData transportData)
    {
        this.client = client;
        this.tracker = tracker;
        this.transportData = transportData;
    }

    private static int key(int x, int y)
    {
        return x << 8 | y;
    }

    /**
     * Rebuilds both sources for the current scene and plane.
     *
     * @param baseX world x of scene tile 0
     * @param baseY world y of scene tile 0
     * @param instance true inside instanced regions, where world coordinates don't map to the table
     */
    public void rebuild(int width, int height, int baseX, int baseY, int plane, boolean instance)
    {
        rebuildData(width, height, baseX, baseY, plane, instance);
        rebuildObjects(width, height, plane);
    }

    private void rebuildData(int width, int height, int baseX, int baseY, int plane, boolean instance)
    {
        dataFrom.clear();
        if (instance)
        {
            return;
        }

        Map<Integer, List<Integer>> tmp = new HashMap<>();
        for (TransportData.Transport t : transportData.all())
        {
            if (t.plane != plane)
            {
                continue;
            }
            int sx = t.ox - baseX, sy = t.oy - baseY, tx = t.dx - baseX, ty = t.dy - baseY;
            if (sx < 0 || sy < 0 || sx >= width || sy >= height
                || tx < 0 || ty < 0 || tx >= width || ty >= height)
            {
                continue;
            }
            if (!t.skillsMet(client))
            {
                continue;
            }
            List<Integer> list = tmp.computeIfAbsent(key(sx, sy), k -> new ArrayList<>(2));
            list.add(tx);
            list.add(ty);
        }
        for (Map.Entry<Integer, List<Integer>> e : tmp.entrySet())
        {
            dataFrom.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
    }

    private void rebuildObjects(int width, int height, int plane)
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
    public int[] from(int x, int y)
    {
        return dataFrom.get(key(x, y));
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
