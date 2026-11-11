package com.collisionmap;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import javax.inject.Inject;

import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the walls around the area you can reach, plus the doors and shortcuts that lead out of it.
 * Which tiles you can reach is worked out in {@link Reachability} at most once per game tick; this
 * class only turns that into pixels.
 *
 * <p>What makes it cheap (all measured, Java2D on a 1080p image):
 * <ul>
 *   <li><b>Blend mode.</b> With the GPU plugin on, RuneLite uploads the overlay buffer as a texture and
 *       the GPU blends it over the scene, so Java2D doesn't have to blend against what's underneath.
 *       Drawing with {@code AlphaComposite.Src} instead of the default {@code SrcOver} made lines about
 *       20x cheaper. Without the GPU plugin the buffer holds the game frame itself and has to be blended,
 *       so we draw into our own transparent layer with {@code Src} and blit the used region once; that
 *       is still ~9x cheaper than blending every line. (This is also what LimeyTileMan's "GPU acceleration" is.)</li>
 *   <li><b>Fewer lines.</b> Straight runs of tile edges are merged into single lines ({@link RunMerger}).</li>
 *   <li><b>Less projection.</b> Each tile corner is projected at most once per frame and anything off
 *       screen is dropped before drawing.</li>
 * </ul>
 */
public class CollisionMapOverlay extends Overlay
{
    // Tile corners are numbered SW, SE, NE, NW; edges are stored on the corner grid instead.
    private static final byte WALL = 1, DOOR = 2; // a higher code wins when two tiles disagree about an edge

    private static final int TILE = Perspective.LOCAL_TILE_SIZE;
    private static final int ESCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;

    private final Client client;
    private final CollisionMapConfig config;
    private final MapState state;

    // ---- settings snapshot (config proxy calls are slow, so read them only when something changed)
    private int snapshotVersion = -1;
    private Settings s;

    // ---- per frame scratch (reused, only reallocated when the window grows)
    private WorldView worldView;
    private byte[][][] bridgeSettings;
    private int plane;
    private int canvasW, canvasH;
    private int gridX0, gridY0, gridW, gridH;
    private byte[] cornerState = new byte[0]; // 0 = not projected yet, 1 = ok, 2 = unusable
    private int[] cornerX = new int[0];
    private int[] cornerY = new int[0];
    private byte[] hEdge = new byte[0];       // edge from corner (gx,gy) to (gx+1,gy)
    private byte[] vEdge = new byte[0];       // edge from corner (gx,gy) to (gx,gy+1)
    private int outX, outY;                   // result of corner()

    private final RunMerger merger = new RunMerger();
    private final int[] runX = new int[512];
    private final int[] runY = new int[512];
    private final boolean[] runOk = new boolean[512];
    private Graphics2D lineGraphics;
    private final RunMerger.Sink lineSink = (x1, y1, x2, y2) ->
    {
        if (!offScreen(x1, y1, x2, y2))
        {
            lineGraphics.drawLine(x1, y1, x2, y2);
            touch(x1, y1);
            touch(x2, y2);
        }
    };

    // ---- bounds of what was drawn this frame / last frame (for the software-mode layer)
    private int minX, minY, maxX, maxY;
    private int lastMinX = 1, lastMinY, lastMaxX, lastMaxY; // lastMinX > lastMaxX means "nothing to clear"

    // ---- software-mode layer
    private BufferedImage layer;
    private int[] layerPixels;
    private Graphics2D layerGraphics;

    @Inject
    private CollisionMapOverlay(Client client, CollisionMapConfig config, MapState state)
    {
        this.client = client;
        this.config = config;
        this.state = state;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(-1f); // draw before other overlays: with Src blending, whoever draws last wins a pixel
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (snapshotVersion != state.configVersion())
        {
            snapshotVersion = state.configVersion();
            s = new Settings(config);
        }

        Reachability.Result result = state.get();
        WorldView wv = client.getTopLevelWorldView();
        Player player = client.getLocalPlayer();
        if (result == null || wv == null || player == null || wv.getScene() == null)
        {
            return null;
        }

        CollisionData[] maps = wv.getCollisionMaps();
        plane = wv.getPlane();
        if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null)
        {
            return null;
        }
        int[][] flags = maps[plane].getFlags();
        if (result.tier.length != flags.length || result.tier[0].length != flags[0].length)
        {
            return null; // stale result from a previous scene
        }

        canvasW = client.getCanvasWidth();
        canvasH = client.getCanvasHeight();
        if (canvasW <= 0 || canvasH <= 0)
        {
            return null;
        }

        LocalPoint lp = player.getLocalLocation();
        int r = s.radius;
        int tileMinX = Math.max(0, lp.getSceneX() - r), tileMaxX = Math.min(flags.length - 1, lp.getSceneX() + r);
        int tileMinY = Math.max(0, lp.getSceneY() - r), tileMaxY = Math.min(flags[0].length - 1, lp.getSceneY() + r);
        if (tileMinX > tileMaxX || tileMinY > tileMaxY)
        {
            return null;
        }

        worldView = wv;
        bridgeSettings = wv.getScene().getExtendedTileSettings();
        prepareGrid(tileMinX, tileMinY, tileMaxX, tileMaxY);
        buildEdges(flags, result, tileMinX, tileMaxX, tileMinY, tileMaxY);

        minX = Integer.MAX_VALUE;
        minY = Integer.MAX_VALUE;
        maxX = Integer.MIN_VALUE;
        maxY = Integer.MIN_VALUE;

        if (client.isGpu())
        {
            // The GPU blends this buffer over the scene, so just overwrite pixels.
            Composite previous = g.getComposite();
            g.setComposite(AlphaComposite.Src);
            try
            {
                paint(g, result, tileMinX, tileMaxX, tileMinY, tileMaxY);
            }
            finally
            {
                g.setComposite(previous);
            }
        }
        else
        {
            // The buffer is the game frame: draw to a transparent layer, then blend it in once.
            Graphics2D lg = prepareLayer(g);
            paint(lg, result, tileMinX, tileMaxX, tileMinY, tileMaxY);
            blitLayer(g);
        }
        return null;
    }

    // ------------------------------------------------------------------ building geometry

    private void buildEdges(int[][] flags, Reachability.Result res, int tMinX, int tMaxX, int tMinY, int tMaxY)
    {
        byte[][] tier = res.tier;
        byte[][] passageEdges = res.passageEdges;

        for (int x = tMinX; x <= tMaxX; x++)
        {
            for (int y = tMinY; y <= tMaxY; y++)
            {
                if (tier[x][y] == Reachability.NONE)
                {
                    continue;
                }
                for (int d = 0; d < 4; d++)
                {
                    if ((passageEdges[x][y] >> d & 1) != 0)
                    {
                        setTileEdge(x, y, d, DOOR);
                        continue;
                    }
                    int nx = x + Reachability.DX[d], ny = y + Reachability.DY[d];
                    boolean neighbourReached = nx >= 0 && ny >= 0 && nx < tier.length && ny < tier[0].length
                        && tier[nx][ny] != Reachability.NONE;
                    if (!neighbourReached || Reachability.edgeBlocked(flags, x, y, d))
                    {
                        setTileEdge(x, y, d, WALL);
                    }
                }
            }
        }
    }

    private void setTileEdge(int x, int y, int dir, byte code)
    {
        switch (dir)
        {
            case Reachability.N:
                raise(hEdge, x, y + 1, code);
                break;
            case Reachability.E:
                raise(vEdge, x + 1, y, code);
                break;
            case Reachability.S:
                raise(hEdge, x, y, code);
                break;
            default:
                raise(vEdge, x, y, code);
                break;
        }
    }

    private void raise(byte[] edges, int cornerX, int cornerY, byte code)
    {
        int idx = (cornerX - gridX0) * gridH + (cornerY - gridY0);
        if (code > edges[idx])
        {
            edges[idx] = code;
        }
    }

    // ------------------------------------------------------------------ painting

    private void paint(Graphics2D g, Reachability.Result res, int tMinX, int tMaxX, int tMinY, int tMaxY)
    {
        Stroke previous = g.getStroke();
        g.setStroke(s.wallStroke);
        drawEdges(g, WALL, s.wallColor);
        drawEdges(g, DOOR, s.passageColor);

        // Shortcuts and crossings that don't sit on a shared edge: a dashed line to where they lead.
        g.setStroke(s.dashedStroke);
        g.setColor(s.passageColor);
        for (Reachability.Link link : res.links)
        {
            if (link.adjacent())
            {
                continue;
            }
            boolean fromIn = link.fromX >= tMinX && link.fromX <= tMaxX && link.fromY >= tMinY && link.fromY <= tMaxY;
            boolean toIn = link.toX >= tMinX && link.toX <= tMaxX && link.toY >= tMinY && link.toY <= tMaxY;
            if ((!fromIn && !toIn) || !centre(link.fromX, link.fromY))
            {
                continue;
            }
            int fx = outX, fy = outY;
            if (!centre(link.toX, link.toY) || offScreen(fx, fy, outX, outY))
            {
                continue;
            }
            g.drawLine(fx, fy, outX, outY);
            g.fillOval(outX - 3, outY - 3, 7, 7);
            touch(fx, fy);
            touch(outX + 4, outY + 4);
            touch(outX - 4, outY - 4);
        }
        g.setStroke(previous);
    }

    /** Draws every edge with the given code, merging straight runs into single lines. */
    private void drawEdges(Graphics2D g, byte code, Color color)
    {
        g.setColor(color);
        lineGraphics = g;

        for (int gy = 0; gy < gridH; gy++)
        {
            int gx = 0;
            while (gx < gridW)
            {
                if (hEdge[gx * gridH + gy] != code)
                {
                    gx++;
                    continue;
                }
                int start = gx;
                while (gx < gridW && hEdge[gx * gridH + gy] == code)
                {
                    gx++;
                }
                emitRun(gridX0 + start, gridY0 + gy, gx - start, true);
            }
        }
        for (int gx = 0; gx < gridW; gx++)
        {
            int gy = 0;
            while (gy < gridH)
            {
                if (vEdge[gx * gridH + gy] != code)
                {
                    gy++;
                    continue;
                }
                int start = gy;
                while (gy < gridH && vEdge[gx * gridH + gy] == code)
                {
                    gy++;
                }
                emitRun(gridX0 + gx, gridY0 + start, gy - start, false);
            }
        }
    }

    private void emitRun(int cx, int cy, int edges, boolean horizontal)
    {
        int n = Math.min(edges + 1, runX.length);
        for (int k = 0; k < n; k++)
        {
            boolean ok = horizontal ? corner(cx + k, cy) : corner(cx, cy + k);
            runOk[k] = ok;
            runX[k] = outX;
            runY[k] = outY;
        }
        merger.merge(runX, runY, runOk, n, lineSink);
    }

    // ------------------------------------------------------------------ software-mode layer

    private Graphics2D prepareLayer(Graphics2D target)
    {
        if (layer == null || layer.getWidth() != canvasW || layer.getHeight() != canvasH)
        {
            if (layerGraphics != null)
            {
                layerGraphics.dispose();
            }
            layer = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
            layerPixels = ((DataBufferInt) layer.getRaster().getDataBuffer()).getData();
            layerGraphics = layer.createGraphics();
            layerGraphics.setComposite(AlphaComposite.Src);
            lastMinX = 1;
            lastMaxX = 0;
        }
        else
        {
            // Wipe only what we drew last frame.
            for (int y = lastMinY; lastMinX <= lastMaxX && y <= lastMaxY; y++)
            {
                Arrays.fill(layerPixels, y * canvasW + lastMinX, y * canvasW + lastMaxX + 1, 0);
            }
        }
        layerGraphics.setRenderingHints(target.getRenderingHints());
        return layerGraphics;
    }

    private void blitLayer(Graphics2D target)
    {
        int pad = s.pad;
        int x0 = Math.max(0, minX - pad), y0 = Math.max(0, minY - pad);
        int x1 = Math.min(canvasW - 1, maxX + pad), y1 = Math.min(canvasH - 1, maxY + pad);
        if (x0 > x1 || y0 > y1)
        {
            lastMinX = 1;
            lastMaxX = 0;
            return;
        }
        target.drawImage(layer.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1), x0, y0, null);
        lastMinX = x0;
        lastMinY = y0;
        lastMaxX = x1;
        lastMaxY = y1;
    }

    private void touch(int x, int y)
    {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
    }

    // ------------------------------------------------------------------ projection

    private void prepareGrid(int tMinX, int tMinY, int tMaxX, int tMaxY)
    {
        gridX0 = tMinX;
        gridY0 = tMinY;
        gridW = tMaxX - tMinX + 2; // corners, so one more than tiles
        gridH = tMaxY - tMinY + 2;
        int n = gridW * gridH;
        if (cornerState.length < n)
        {
            cornerState = new byte[n];
            cornerX = new int[n];
            cornerY = new int[n];
            hEdge = new byte[n];
            vEdge = new byte[n];
        }
        else
        {
            Arrays.fill(cornerState, 0, n, (byte) 0);
            Arrays.fill(hEdge, 0, n, (byte) 0);
            Arrays.fill(vEdge, 0, n, (byte) 0);
        }
    }

    /**
     * Canvas position of the south-west corner of scene tile (i, j), into outX/outY, using the same
     * height RuneLite's own tile polygons use (including its bridge rule). Corners inside the draw
     * window are cached for the frame; others (long passage links) are projected directly.
     */
    private boolean corner(int i, int j)
    {
        int gx = i - gridX0, gy = j - gridY0;
        boolean cached = gx >= 0 && gy >= 0 && gx < gridW && gy < gridH;
        int idx = gx * gridH + gy;

        if (cached)
        {
            byte st = cornerState[idx];
            if (st == 1)
            {
                outX = cornerX[idx];
                outY = cornerY[idx];
                return true;
            }
            if (st == 2)
            {
                return false;
            }
        }

        int mapLevel = plane;
        int msx = i + ESCENE_OFFSET, msy = j + ESCENE_OFFSET;
        if (plane < Constants.MAX_Z - 1 && msx >= 0 && msy >= 0
            && msx < bridgeSettings[1].length && msy < bridgeSettings[1][0].length
            && (bridgeSettings[1][msx][msy] & Constants.TILE_FLAG_BRIDGE) == Constants.TILE_FLAG_BRIDGE)
        {
            mapLevel = plane + 1;
        }
        int lx = i * TILE, ly = j * TILE;
        Point p = Perspective.localToCanvas(client, lx, ly, worldView.getTileHeight(lx, ly, mapLevel));
        if (p == null)
        {
            if (cached)
            {
                cornerState[idx] = 2;
            }
            return false;
        }

        outX = p.getX();
        outY = p.getY();
        if (cached)
        {
            cornerX[idx] = outX;
            cornerY[idx] = outY;
            cornerState[idx] = 1;
        }
        return true;
    }

    /** Centre of a tile's projected quad into outX/outY. */
    private boolean centre(int x, int y)
    {
        int sx = 0, sy = 0;
        for (int k = 0; k < 4; k++)
        {
            if (!corner(x + (k == 1 || k == 2 ? 1 : 0), y + (k >= 2 ? 1 : 0)))
            {
                return false;
            }
            sx += outX;
            sy += outY;
        }
        outX = sx / 4;
        outY = sy / 4;
        return true;
    }

    private boolean offScreen(int ax, int ay, int bx, int by)
    {
        return (ax < 0 && bx < 0) || (ax > canvasW && bx > canvasW)
            || (ay < 0 && by < 0) || (ay > canvasH && by > canvasH);
    }

    // ------------------------------------------------------------------ settings snapshot

    private static final class Settings
    {
        final int radius, pad;
        final Color wallColor, passageColor;
        final Stroke wallStroke, dashedStroke;

        Settings(CollisionMapConfig c)
        {
            radius = c.drawRadius();
            wallColor = c.wallColor();
            passageColor = c.passageColor();
            int thickness = c.wallThickness();
            pad = thickness + 2;
            wallStroke = new BasicStroke(thickness, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
            dashedStroke = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                new float[]{6f, 5f}, 0f);
        }
    }
}
