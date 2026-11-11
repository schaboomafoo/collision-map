package com.collisionmap;

import java.awt.BasicStroke;
import java.awt.Color;
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
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.Text;

/** Click boxes and labels for doors, gates, shortcuts and anything else with a matching action. */
public class ObjectMarkerOverlay extends Overlay
{
    private final Client client;
    private final CollisionMapConfig config;
    private final ObjectTracker tracker;
    private final MapState state;

    // Config proxy calls are slow, so read the config only when it changed.
    private int snapshotVersion = -1;
    private boolean enabled, labels;
    private int radius;
    private Color colour, soft;
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
            soft = new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 35);
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

        graphics.setStroke(new BasicStroke(1.5f));
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

            Shape box = t.object.getClickbox();
            if (box != null)
            {
                graphics.setColor(soft);
                graphics.fill(box);
                graphics.setColor(colour);
                graphics.draw(box);
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
