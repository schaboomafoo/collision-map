package com.collisionmap;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;

import net.runelite.api.Client;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.Text;

/**
 * Outlines and labels for doors, gates, shortcuts and anything else with a matching action.
 *
 * <p>Outlines are never filled: a translucent fill has to be blended pixel by pixel and was the
 * bulk of the cost. With the GPU plugin on, outlines are drawn with {@code AlphaComposite.Src} like
 * the walls (see {@link CollisionMapOverlay}). The {@link CollisionMapConfig.BoxStyle#TILES} style
 * skips projecting the object's 3D model ({@code getClickbox()}) entirely.
 */
public class ObjectMarkerOverlay extends Overlay
{
    private final Client client;
    private final CollisionMapConfig config;
    private final ObjectTracker tracker;
    private final MapState state;

    private static final BasicStroke STROKE = new BasicStroke(1.5f);

    // Config proxy calls are slow, so read the config only when it changed.
    private int snapshotVersion = -1;
    private boolean enabled, labels;
    private CollisionMapConfig.BoxStyle style;
    private int radius;
    private Color colour;
    private String[] filters = new String[0];

    // The list of objects to draw, rebuilt at most once per game tick.
    private static final class Marked
    {
        final ObjectTracker.Tracked tracked;
        final String label;

        Marked(ObjectTracker.Tracked tracked, String label)
        {
            this.tracked = tracked;
            this.label = label;
        }
    }

    private final List<Marked> marked = new ArrayList<>();
    private Shape[] boxes = new Shape[64];
    private int builtTick = -1, builtConfig = -1, builtTracker = -1, builtPlane = -1;

    @Inject
    private ObjectMarkerOverlay(Client client, CollisionMapConfig config, ObjectTracker tracker, MapState state)
    {
        this.client = client;
        this.config = config;
        this.tracker = tracker;
        this.state = state;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (snapshotVersion != state.configVersion())
        {
            snapshotVersion = state.configVersion();
            enabled = config.showObjects();
            labels = config.objectLabels();
            radius = config.drawRadius();
            colour = config.objectColor();
            style = config.boxStyle();
            filters = Arrays.stream(config.actionFilter().split(","))
                .map(f -> f.trim().toLowerCase(Locale.ROOT))
                .filter(f -> !f.isEmpty())
                .toArray(String[]::new);
        }
        if (!enabled || filters.length == 0)
        {
            return null;
        }

        WorldView wv = client.getTopLevelWorldView();
        if (wv == null)
        {
            return null;
        }
        int plane = wv.getPlane();

        // Deciding which objects to mark (impostor lookups, string matching, label text) only
        // changes on game ticks, so do it at most once per tick, like tile markers keep a ready list.
        int tick = client.getTickCount();
        if (tick != builtTick || snapshotVersion != builtConfig || tracker.version() != builtTracker || plane != builtPlane)
        {
            if (!rebuild(plane))
            {
                return null;
            }
            builtTick = tick;
            builtConfig = snapshotVersion;
            builtTracker = tracker.version();
            builtPlane = plane;
        }
        if (marked.isEmpty())
        {
            return null;
        }

        int n = marked.size();
        if (boxes.length < n)
        {
            boxes = new Shape[n * 2];
        }

        // Pass 1: outlines. With the GPU plugin on, overwrite pixels instead of blending (see class doc).
        Stroke previousStroke = graphics.getStroke();
        Composite previousComposite = graphics.getComposite();
        graphics.setStroke(STROKE);
        graphics.setColor(colour);
        if (client.isGpu())
        {
            graphics.setComposite(AlphaComposite.Src);
        }
        for (int k = 0; k < n; k++)
        {
            Shape box = outline(marked.get(k).tracked, plane);
            boxes[k] = box;
            if (box != null)
            {
                graphics.draw(box);
            }
        }
        graphics.setComposite(previousComposite);
        graphics.setStroke(previousStroke);

        // Pass 2: labels, blended normally so their anti-aliased edges look right.
        if (labels)
        {
            FontMetrics fm = graphics.getFontMetrics();
            for (int k = 0; k < n; k++)
            {
                Marked m = marked.get(k);
                Point at;
                if (boxes[k] != null)
                {
                    Rectangle b = boxes[k].getBounds();
                    at = new Point(b.x + b.width / 2 - fm.stringWidth(m.label) / 2, b.y - 4);
                }
                else
                {
                    at = m.tracked.object.getCanvasTextLocation(graphics, m.label, 0);
                }
                if (at != null)
                {
                    OverlayUtil.renderTextLocation(graphics, at, m.label, colour);
                }
            }
        }
        Arrays.fill(boxes, 0, n, null);
        return null;
    }

    /** Refills {@link #marked} with the objects to draw around the player. False if there is no player. */
    private boolean rebuild(int plane)
    {
        marked.clear();
        LocalPoint lp = PlayerPosition.onTopLevel(client);
        if (lp == null)
        {
            return false;
        }
        int px = lp.getSceneX(), py = lp.getSceneY();
        // The player moves at most two tiles per tick, so pad the radius to cover the whole tick.
        int r = radius + 2;

        for (ObjectTracker.Tracked t : tracker.all())
        {
            if (t.plane != plane || t.distanceTo(px, py) > r)
            {
                continue;
            }
            ObjectComposition comp = t.current();
            if (comp == null)
            {
                continue;
            }
            String action = firstMatchingAction(comp.getActions());
            if (action == null)
            {
                continue;
            }
            String label = null;
            if (labels)
            {
                String name = comp.getName() == null ? "" : Text.removeTags(comp.getName());
                label = (name.isEmpty() || "null".equals(name) ? "Object" : name) + " (" + action + ")";
            }
            marked.add(new Marked(t, label));
        }
        return true;
    }

    private Shape outline(ObjectTracker.Tracked t, int plane)
    {
        switch (style)
        {
            case CLICKBOX:
                return t.object.getClickbox();
            case TILES:
                LocalPoint centre = t.object.getLocalLocation(); // game objects: centre of the footprint
                return centre == null ? null
                    : Perspective.getCanvasTileAreaPoly(client, centre, t.sizeX, t.sizeY, plane, 0);
            default:
                return null;
        }
    }

    private String firstMatchingAction(String[] actions)
    {
        if (actions == null)
        {
            return null;
        }
        for (String a : actions)
        {
            if (a == null || a.isEmpty())
            {
                continue;
            }
            String lower = a.toLowerCase(Locale.ROOT);
            for (String f : filters)
            {
                if (lower.startsWith(f))
                {
                    return a;
                }
            }
        }
        return null;
    }
}
