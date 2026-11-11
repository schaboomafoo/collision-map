package com.collisionmap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.runelite.api.CollisionDataFlag;

/**
 * Flood fill over the scene's collision flags, starting at the player's tile.
 *
 * <p>The fill works in "hops". Hop 0 is everything you can walk to right now. Each door, gate or
 * shortcut that leads out of that area is a passage; hop 1 is everything on the far side of those
 * passages, hop 2 the far side of the passages found in hop 1, and so on up to {@code maxHops}.
 * A tile's {@link Result#tier} is {@code hop + 1}, so 0 ({@link #NONE}) means "not reached".
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

    /** Where passages come from. Implemented by {@link PassageIndex}. */
    public interface Passages
    {
        Passages NONE = new Passages()
        {
            @Override
            public int[] from(int x, int y)
            {
                return null;
            }

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

        /** Data-table passages that start on this tile, as a flat {@code [toX, toY, toX, toY...]}, or null. */
        int[] from(int x, int y);

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
        /** 0 = not reached, otherwise hop + 1. */
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

    public static Result compute(int[][] flags, int startX, int startY, int maxHops, Passages passages)
    {
        return new Reachability(flags, passages, maxHops).run(startX, startY);
    }

    // ------------------------------------------------------------------ implementation

    private final int[][] flags;
    private final Passages passages;
    private final int maxHops;
    private final int w, h;
    private final byte[][] tier;
    private final byte[][] passageEdges;
    private final List<Link> links = new ArrayList<>();
    private final Set<Long> seenLinks = new HashSet<>();

    private Reachability(int[][] flags, Passages passages, int maxHops)
    {
        this.flags = flags;
        this.passages = passages;
        this.maxHops = Math.max(0, maxHops);
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
        List<Integer> seeds = new ArrayList<>();
        seeds.add(startX * h + startY);

        for (int hop = 0; hop <= maxHops && !seeds.isEmpty(); hop++)
        {
            byte t = (byte) (hop + 1);
            boolean canSeed = hop < maxHops;
            int head = 0, tail = 0;

            for (int s : seeds)
            {
                int x = s / h, y = s % h;
                if (tier[x][y] == NONE)
                {
                    tier[x][y] = t;
                    queue[tail++] = s;
                }
            }
            List<Integer> next = new ArrayList<>();

            while (head < tail)
            {
                int c = queue[head++];
                int x = c / h, y = c % h;

                // Passages from the transport table that start on this tile.
                int[] dp = passages.from(x, y);
                if (dp != null)
                {
                    for (int i = 0; i + 1 < dp.length; i += 2)
                    {
                        int tx = dp[i], ty = dp[i + 1];
                        if (tx < 0 || ty < 0 || tx >= w || ty >= h || fullyBlocked(flags[tx][ty]))
                        {
                            continue;
                        }
                        addLink(x, y, tx, ty);
                        if (canSeed && tier[tx][ty] == NONE)
                        {
                            next.add(tx * h + ty);
                        }
                    }
                }

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
                            tier[nx][ny] = t;
                            queue[tail++] = nx * h + ny;
                        }
                        continue;
                    }

                    if (!nFull)
                    {
                        // Only a wall in the way: a door or gate on this edge?
                        if (passages.canOpenEdge(x, y, d))
                        {
                            addLink(x, y, nx, ny);
                            if (canSeed && tier[nx][ny] == NONE)
                            {
                                next.add(nx * h + ny);
                            }
                        }
                    }
                    else if (passages.canOpenTile(nx, ny))
                    {
                        // A gate-like object occupies the next tile: land on the tile beyond it.
                        int cx = nx + DX[d], cy = ny + DY[d];
                        if (cx >= 0 && cy >= 0 && cx < w && cy < h && !fullyBlocked(flags[cx][cy]))
                        {
                            addLink(x, y, cx, cy);
                            if (canSeed && tier[cx][cy] == NONE)
                            {
                                next.add(cx * h + cy);
                            }
                        }
                    }
                }
            }
            seeds = next;
        }

        return new Result(tier, passageEdges, links);
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
