package com.collisionmap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import net.runelite.api.CollisionDataFlag;
import org.junit.Test;

public class ReachabilityTest
{
    /** 20x5 open field with a solid column at x = 6 (a fence you can't cross). */
    private static int[][] fenced()
    {
        int[][] flags = new int[20][5];
        for (int y = 0; y < 5; y++)
        {
            flags[6][y] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        }
        return flags;
    }

    @Test
    public void offByDefault()
    {
        Reachability.Result r = Reachability.compute(fenced(), 0, 0, 0, Reachability.Passages.NONE);
        assertNotEquals(Reachability.NONE, r.tier[5][2]);
        assertEquals(Reachability.NONE, r.tier[7][2]);
    }

    @Test
    public void fillsAreaWithinRange()
    {
        // x = 7 is 2 tiles from x = 5, so the whole right-hand side is filled, even beyond the range.
        Reachability.Result r = Reachability.compute(fenced(), 0, 0, 0, 2, Reachability.Passages.NONE);
        assertEquals(Reachability.NEARBY, r.tier[7][2]);
        assertEquals(Reachability.NEARBY, r.tier[19][4]);
        assertEquals(Reachability.NONE, r.tier[6][2]);
        assertEquals(1, r.tier[0][0]);
    }

    @Test
    public void ignoresAreaOutOfRange()
    {
        Reachability.Result r = Reachability.compute(fenced(), 0, 0, 0, 1, Reachability.Passages.NONE);
        assertEquals(Reachability.NONE, r.tier[7][2]);
    }

    @Test
    public void respectsWallFlagsInsideNearbyArea()
    {
        int[][] flags = fenced();
        // A wall on the line between x = 9 and x = 10 splits the far side in two.
        for (int y = 0; y < 5; y++)
        {
            flags[9][y] |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
            flags[10][y] |= CollisionDataFlag.BLOCK_MOVEMENT_WEST;
        }
        Reachability.Result r = Reachability.compute(flags, 0, 0, 0, 2, Reachability.Passages.NONE);
        assertEquals(Reachability.NEARBY, r.tier[9][2]);
        assertEquals(Reachability.NONE, r.tier[10][2]);
    }
}
