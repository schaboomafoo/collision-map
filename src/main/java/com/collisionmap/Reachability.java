package com.collisionmap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.runelite.api.CollisionDataFlag;

/**
 * Flood fill over the scene's collision flags, starting at the player's tile.
 *
 * <p>Tiles you can walk to get tier {@link #REACHED}. Doors and gates on the edge of that area are
 * recorded as {@link Link}s so the overlay can colour them, but the fill doesn't go through them.
 *
 * <p>Instead, any separate walkable area that comes within {@code nearbyRange} tiles (straight
 * line, ignoring walls) of your area is filled too, with tier {@link #NEARBY}. That catches the
 * other side of a door, fence or river without knowing which object or shortcut leads there.
 *
 * <p>Only cardinal steps are needed. OSRS only allows a diagonal step when both of the adjacent
 * cardinal steps are also open, so a cardinal fill reaches exactly the same tiles.
 *
 * <p>No RuneLite client objects are used here, only the flag array, so this is cheap to unit test.
 * It is O(scene tiles) and is meant to run when something changes, not every frame.
 */
public final class Reachability
{
    public static final byte NONE = 0;
    /** Tier of a tile you can walk to. */
    public static final byte REACHED = 1;
    /** Tier of a nearby area that was found by distance rather than through a passage. */
    public static final byte NEARBY = -1;

    // Directions. Scene y grows to the north, matching the collision flags.
    public static final int N = 0, E = 1, S = 2, W = 3;
    public static final int[] DX = {0, 1, 0, -1};
    public static final int[] DY = {1, 0, -1, 0};

    // Flag that blocks leaving a tile in direction d, and flag that blocks entering from that side.
    private static final int[] OUT = {
        CollisionDataFlag.BLOCK_MOVEMENT_NORTH, CollisionDataFlag.BLOCK_MOVEMENT_EAST,
        CollisionDataFlag.BLOCK_MOVEMENT_SOUTH, CollisionDataFlag.BLOCK_MOVEMENT_WEST};
    private static final int[] IN = {
        CollisionDataFlag.BLOCK_MOVEMENT_SOUTH, CollisionDataFlag.BLOCK_MOVEMENT_WEST,
        CollisionDataFlag.BLOCK_MOVEMENT_NORTH, CollisionDataFlag.BLOCK_MOVEMENT_EAST};

    public static int opposite(int dir)
    {
        return dir ^ 2;
    }

    /** True if movement between (x,y) and its neighbour in direction {@code dir} is blocked by a wall flag. */
    public static boolean edgeBlocked(int[][] flags, int x, int y, int dir)
    {
        if ((flags[x][y] & OUT[dir]) != 0)
        {
            return true;
        }
        int nx = x + DX[dir], ny = y + DY[dir];
        return nx >= 0 && ny >= 0 && nx < flags.length && ny < flags[0].length
            && (flags[nx][ny] & IN[dir]) != 0;
    }

    public static boolean fullyBlocked(int flag)
    {
        return (flag & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0;
    }

    /** Where doors and gates are. Implemented by {@link PassageIndex}. */
    public interface Passages
    {
        Passages NONE = new Passages()
        {
            @Override
            public boolean canOpenEdge(int x, int y, int dir)
            {
                return false;
            }

            @Override
            public boolean canOpenTile(int x, int y)
            {
                return false;
            }
        };

        /** True if the wall on the edge leaving (x,y) towards {@code dir} is a door/gate you can open. */
        boolean canOpenEdge(int x, int y, int dir);

        /** True if this fully blocked tile is a gate-like object you can open and walk through. */
        boolean canOpenTile(int x, int y);
    }

    public static final class Link
    {
        public final int fromX, fromY, toX, toY;

        Link(int fromX, int fromY, int toX, int toY)
        {
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
        }

        /** Adjacent links are drawn as a coloured edge, longer ones as two highlighted tiles and a line. */
        public boolean adjacent()
        {
            return Math.abs(fromX - toX) + Math.abs(fromY - toY) == 1;
        }
    }

    public static final class Result
    {
        /** {@link #NONE}, {@link #REACHED} or {@link #NEARBY}. */
        public final byte[][] tier;
        /** Per tile bitmask (1 << dir) of edges that are passages, so the overlay can colour them. */
        public final byte[][] passageEdges;
        public final List<Link> links;

        Result(byte[][] tier, byte[][] passageEdges, List<Link> links)
        {
            this.tier = tier;
            this.passageEdges = passageEdges;
            this.links = links;
        }

        public boolean reached(int x, int y)
        {
            return x >= 0 && y >= 0 && x < tier.length && y < tier[0].length && tier[x][y] != NONE;
        }
    }

    public static Result compute(int[][] flags, int startX, int startY, int nearbyRange, Passages passages)
    {
        Reachability r = new Reachability(flags, passages);
        Result result = r.run(startX, startY);
        r.fillNearby(nearbyRange);
        return result;
    }

    // ------------------------------------------------------------------ implementation

    private final int[][] flags;
    private final Passages passages;
    private final int w, h;
    private final byte[][] tier;
    private final byte[][] passageEdges;
    private final List<Link> links = new ArrayList<>();
    private final Set<Long> seenLinks = new HashSet<>();

    private Reachability(int[][] flags, Passages passages)
    {
        this.flags = flags;
        this.passages = passages;
        this.w = flags.length;
        this.h = w == 0 ? 0 : flags[0].length;
        this.tier = new byte[w][h];
        this.passageEdges = new byte[w][h];
    }

    private Result run(int startX, int startY)
    {
        if (startX < 0 || startY < 0 || startX >= w || startY >= h)
        {
            return new Result(tier, passageEdges, links);
        }

        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        tier[startX][startY] = REACHED;
        queue[tail++] = startX * h + startY;

        while (head < tail)
        {
            int c = queue[head++];
            int x = c / h, y = c % h;

            for (int d = 0; d < 4; d++)
            {
                int nx = x + DX[d], ny = y + DY[d];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h)
                {
                    continue;
                }

                boolean nFull = fullyBlocked(flags[nx][ny]);
                boolean wall = edgeBlocked(flags, x, y, d);

                if (!nFull && !wall)
                {
                    if (tier[nx][ny] == NONE)
                    {
                        tier[nx][ny] = REACHED;
                        queue[tail++] = nx * h + ny;
                    }
                }
                else if (!nFull)
                {
                    // Only a wall in the way: a door or gate on this edge?
                    if (passages.canOpenEdge(x, y, d))
                    {
                        addLink(x, y, nx, ny);
                    }
                }
                else if (passages.canOpenTile(nx, ny))
                {
                    // A gate-like object occupies the next tile: link to the tile beyond it.
                    int cx = nx + DX[d], cy = ny + DY[d];
                    if (cx >= 0 && cy >= 0 && cx < w && cy < h && !fullyBlocked(flags[cx][cy]))
                    {
                        addLink(x, y, cx, cy);
                    }
                }
            }
        }

        return new Result(tier, passageEdges, links);
    }

    /**
     * Fills every walkable area that has a tile within {@code range} tiles (Chebyshev, through walls)
     * of a tile reached so far. Linear in scene size: the "within range" test is a box dilation done
     * as two sliding-window passes.
     */
    private void fillNearby(int range)
    {
        if (range <= 0 || w == 0 || h == 0)
        {
            return;
        }

        // rows[x][y] = number of reached tiles in x-range..x+range on row y
        int[][] rows = new int[w][h];
        for (int y = 0; y < h; y++)
        {
            int count = 0;
            for (int x = 0; x < Math.min(range, w); x++)
            {
                count += tier[x][y] != NONE ? 1 : 0;
            }
            for (int x = 0; x < w; x++)
            {
                int in = x + range, out = x - range - 1;
                if (in < w && tier[in][y] != NONE) count++;
                if (out >= 0 && tier[out][y] != NONE) count--;
                rows[x][y] = count;
            }
        }

        // Same along columns over rows; > 0 means a reached tile is within the box.
        boolean[][] near = new boolean[w][h];
        for (int x = 0; x < w; x++)
        {
            int[] col = rows[x];
            int count = 0;
            for (int y = 0; y < Math.min(range, h); y++)
            {
                count += col[y];
            }
            for (int y = 0; y < h; y++)
            {
                int in = y + range, out = y - range - 1;
                if (in < h) count += col[in];
                if (out >= 0) count -= col[out];
                near[x][y] = count > 0;
            }
        }

        int[] queue = new int[w * h];
        for (int sx = 0; sx < w; sx++)
        {
            for (int sy = 0; sy < h; sy++)
            {
                if (!near[sx][sy] || tier[sx][sy] != NONE || fullyBlocked(flags[sx][sy]))
                {
                    continue;
                }
                int head = 0, tail = 0;
                tier[sx][sy] = NEARBY;
                queue[tail++] = sx * h + sy;
                while (head < tail)
                {
                    int c = queue[head++];
                    int x = c / h, y = c % h;
                    for (int d = 0; d < 4; d++)
                    {
                        int nx = x + DX[d], ny = y + DY[d];
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h || tier[nx][ny] != NONE
                            || fullyBlocked(flags[nx][ny]) || edgeBlocked(flags, x, y, d))
                        {
                            continue;
                        }
                        tier[nx][ny] = NEARBY;
                        queue[tail++] = nx * h + ny;
                    }
                }
            }
        }
    }

    private void addLink(int fx, int fy, int tx, int ty)
    {
        int dx = tx - fx, dy = ty - fy;
        if (Math.abs(dx) + Math.abs(dy) == 1)
        {
            int d = dx == 1 ? E : dx == -1 ? W : dy == 1 ? N : S;
            int bit = 1 << d;
            if ((passageEdges[fx][fy] & bit) != 0)
            {
                return;
            }
            passageEdges[fx][fy] |= bit;
            passageEdges[tx][ty] |= 1 << opposite(d);
            links.add(new Link(fx, fy, tx, ty));
            return;
        }

        long a = (long) fx * h + fy, b = (long) tx * h + ty;
        long key = Math.min(a, b) << 32 | Math.max(a, b);
        if (seenLinks.add(key))
        {
            links.add(new Link(fx, fy, tx, ty));
        }
    }
}
