package com.collisionmap;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
    name = "Collision Map",
    description = "Outlines the area you can walk to, plus the doors and shortcuts that lead out of it",
    tags = {"collision", "walls", "doors", "shortcuts", "map"}
)
public class CollisionMapPlugin extends Plugin
{
    /** Even if nothing we know about changed, recompute this often in case flags changed under us. */
    private static final int FORCE_REFRESH_TICKS = 5;

    @Inject
    private Client client;

    @Inject
    private CollisionMapConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private CollisionMapOverlay collisionMapOverlay;

    @Inject
    private ObjectMarkerOverlay objectMarkerOverlay;

    @Inject
    private ObjectTracker objectTracker;

    @Inject
    private TransportData transportData;

    @Inject
    private PassageIndex passageIndex;

    @Inject
    private MapState mapState;

    // What the last computation was based on. If none of it changed, the result is still valid.
    private int lastSceneX = -1, lastSceneY = -1, lastPlane = -1, lastBaseX = -1, lastBaseY = -1;
    private int lastTrackerVersion = -1;
    private int inputVersion;      // bumped by config / skill changes
    private int lastInputVersion = -1;
    private int ticksSinceCompute;
    private final int[] knownLevels = new int[Skill.values().length];

    @Override
    protected void startUp() throws Exception
    {
        transportData.load();
        invalidate();
        overlayManager.add(collisionMapOverlay);
        overlayManager.add(objectMarkerOverlay);
        log.info("Collision Map started!");
    }

    @Override
    protected void shutDown() throws Exception
    {
        overlayManager.remove(collisionMapOverlay);
        overlayManager.remove(objectMarkerOverlay);
        objectTracker.clear();
        mapState.set(null);
        log.info("Collision Map stopped!");
    }

    @Provides
    CollisionMapConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(CollisionMapConfig.class);
    }

    // ------------------------------------------------------------------ recompute triggers

    @Subscribe
    public void onGameTick(GameTick event)
    {
        recompute();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if ("collisionmap".equals(event.getGroup()))
        {
            mapState.bumpConfigVersion();
            invalidate();
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        // Only a level change can unlock or lock a shortcut.
        int i = event.getSkill().ordinal();
        if (knownLevels[i] != event.getLevel())
        {
            knownLevels[i] = event.getLevel();
            invalidate();
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState state = event.getGameState();
        if (state == GameState.LOADING || state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
        {
            // A new scene is on its way; spawn events will repopulate the tracker.
            objectTracker.clear();
            mapState.set(null);
            invalidate();
        }
    }

    private void invalidate()
    {
        inputVersion++;
    }

    // ------------------------------------------------------------------ object tracking

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned e)
    {
        objectTracker.add(e.getGameObject());
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned e)
    {
        objectTracker.remove(e.getGameObject());
    }

    @Subscribe
    public void onWallObjectSpawned(WallObjectSpawned e)
    {
        objectTracker.add(e.getWallObject());
    }

    @Subscribe
    public void onWallObjectDespawned(WallObjectDespawned e)
    {
        objectTracker.remove(e.getWallObject());
    }

    @Subscribe
    public void onDecorativeObjectSpawned(DecorativeObjectSpawned e)
    {
        objectTracker.add(e.getDecorativeObject());
    }

    @Subscribe
    public void onDecorativeObjectDespawned(DecorativeObjectDespawned e)
    {
        objectTracker.remove(e.getDecorativeObject());
    }

    @Subscribe
    public void onGroundObjectSpawned(GroundObjectSpawned e)
    {
        objectTracker.add(e.getGroundObject());
    }

    @Subscribe
    public void onGroundObjectDespawned(GroundObjectDespawned e)
    {
        objectTracker.remove(e.getGroundObject());
    }

    // ------------------------------------------------------------------ the actual work

    private void recompute()
    {
        WorldView wv = client.getTopLevelWorldView();
        Player player = client.getLocalPlayer();
        if (wv == null || player == null || client.getGameState() != GameState.LOGGED_IN)
        {
            mapState.set(null);
            return;
        }

        CollisionData[] maps = wv.getCollisionMaps();
        int plane = wv.getPlane();
        if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null)
        {
            mapState.set(null);
            return;
        }

        LocalPoint lp = player.getLocalLocation();
        int sx = lp.getSceneX(), sy = lp.getSceneY();
        int baseX = wv.getBaseX(), baseY = wv.getBaseY();

        boolean unchanged = mapState.get() != null
            && sx == lastSceneX && sy == lastSceneY && plane == lastPlane
            && baseX == lastBaseX && baseY == lastBaseY
            && objectTracker.version() == lastTrackerVersion
            && inputVersion == lastInputVersion
            && ++ticksSinceCompute < FORCE_REFRESH_TICKS;
        if (unchanged)
        {
            return;
        }

        int[][] flags = maps[plane].getFlags();
        int width = flags.length, height = width == 0 ? 0 : flags[0].length;

        passageIndex.rebuild(width, height, baseX, baseY, plane, client.isInInstancedRegion());

        mapState.set(Reachability.compute(flags, sx, sy, config.doorsToFollow(), passageIndex));

        lastSceneX = sx;
        lastSceneY = sy;
        lastPlane = plane;
        lastBaseX = baseX;
        lastBaseY = baseY;
        lastTrackerVersion = objectTracker.version();
        lastInputVersion = inputVersion;
        ticksSinceCompute = 0;
    }
}
