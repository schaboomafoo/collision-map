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
import java.util.Arrays;
import java.util.Locale;
import javax.inject.Inject;

import net.runelite.api.Client;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
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
        Player player = client.getLocalPlayer();
        if (wv == null || player == null)
        {
            return null;
        }

        LocalPoint lp = player.getLocalLocation();
        int px = lp.getSceneX(), py = lp.getSceneY();
        int plane = wv.getPlane();

        graphics.setStroke(STROKE);
        boolean gpu = client.isGpu();
        FontMetrics fm = graphics.getFontMetrics();

        for (ObjectTracker.Tracked t : tracker.all())
        {
            // Cheap integer checks first; the composition lookup only happens for nearby objects.
            if (t.plane != plane || t.distanceTo(px, py) > radius)
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

            Shape box = outline(t, plane);
            if (box != null)
            {
                graphics.setColor(colour);
                if (gpu)
                {
                    // The GPU blends this buffer over the scene, so just overwrite pixels.
                    Composite previous = graphics.getComposite();
                    graphics.setComposite(AlphaComposite.Src);
                    graphics.draw(box);
                    graphics.setComposite(previous);
                }
                else
                {
                    graphics.draw(box);
                }
            }

            if (labels)
            {
                String name = comp.getName() == null ? "" : Text.removeTags(comp.getName());
                String text = (name.isEmpty() || "null".equals(name) ? "Object" : name) + " (" + action + ")";

                Point at = null;
                if (box != null)
                {
                    Rectangle b = box.getBounds();
                    at = new Point(b.x + b.width / 2 - fm.stringWidth(text) / 2, b.y - 4);
                }
                else
                {
                    at = t.object.getCanvasTextLocation(graphics, text, 0);
                }
                if (at != null)
                {
                    OverlayUtil.renderTextLocation(graphics, at, text, colour);
                }
            }
        }
        return null;
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
