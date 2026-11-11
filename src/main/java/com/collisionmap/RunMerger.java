package com.collisionmap;

/**
 * Turns a row of projected tile corners into as few straight lines as possible.
 *
 * <p>A wall along a row of tiles is many unit edges, but on flat ground they all project onto one
 * straight screen line. Java2D's cost goes up with segment count, so this merges consecutive edges
 * whenever every intermediate corner stays within a pixel or so of the straight line between the ends.
 * On sloped terrain the corners bend away from the line, the run is split there, and the line still
 * follows the ground.
 *
 * <p>Pure geometry with no RuneLite types, so it can be unit tested on its own.
 */
final class RunMerger
{
    interface Sink
    {
        void line(int x1, int y1, int x2, int y2);
    }

    /** Max squared distance, in pixels, an intermediate corner may sit from the merged line. */
    static final double TOLERANCE_SQ = 2.25;

    private final int[] midX = new int[512];
    private final int[] midY = new int[512];

    /**
     * @param xs, ys canvas position of each corner along the run, in order
     * @param ok     false where a corner couldn't be projected (behind the camera); the run is broken there
     * @param n      number of corners (edges + 1)
     * @return number of lines emitted
     */
    int merge(int[] xs, int[] ys, boolean[] ok, int n, Sink sink)
    {
        int lines = 0;
        boolean haveStart = false, haveEnd = false;
        int sx = 0, sy = 0, ex = 0, ey = 0;
        int mids = 0;

        for (int k = 0; k < n; k++)
        {
            if (!ok[k])
            {
                if (haveEnd)
                {
                    sink.line(sx, sy, ex, ey);
                    lines++;
                }
                haveStart = false;
                haveEnd = false;
                mids = 0;
                continue;
            }

            int x = xs[k], y = ys[k];
            if (!haveStart)
            {
                sx = x;
                sy = y;
                haveStart = true;
                haveEnd = false;
                mids = 0;
                continue;
            }
            if (!haveEnd)
            {
                ex = x;
                ey = y;
                haveEnd = true;
                continue;
            }

            // Try to stretch start..end to start..(x,y). The old end becomes an intermediate corner.
            if (mids < midX.length - 1 && fits(sx, sy, x, y, ex, ey, mids))
            {
                midX[mids] = ex;
                midY[mids] = ey;
                mids++;
                ex = x;
                ey = y;
            }
            else
            {
                sink.line(sx, sy, ex, ey);
                lines++;
                sx = ex;
                sy = ey;
                ex = x;
                ey = y;
                mids = 0;
            }
        }

        if (haveEnd)
        {
            sink.line(sx, sy, ex, ey);
            lines++;
        }
        return lines;
    }

    /** True if all stored intermediates and the current end lie close enough to the line start..(x,y). */
    private boolean fits(int sx, int sy, int x, int y, int ex, int ey, int mids)
    {
        double dx = x - sx, dy = y - sy;
        double len2 = dx * dx + dy * dy;
        if (len2 == 0)
        {
            return false;
        }
        double limit = TOLERANCE_SQ * len2;
        for (int i = 0; i < mids; i++)
        {
            double c = dx * (midY[i] - sy) - dy * (midX[i] - sx);
            if (c * c > limit)
            {
                return false;
            }
        }
        double c = dx * (ey - sy) - dy * (ex - sx);
        return c * c <= limit;
    }
}
