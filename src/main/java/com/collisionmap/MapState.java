package com.collisionmap;

import javax.inject.Singleton;

/** Hand-off between the plugin (which computes) and the overlay (which draws). */
@Singleton
public class MapState
{
    private volatile Reachability.Result result;
    private volatile int configVersion;

    /** Bumped whenever the plugin config changes, so overlays know to re-read their cached settings. */
    public int configVersion()
    {
        return configVersion;
    }

    public void bumpConfigVersion()
    {
        configVersion++;
    }

    public Reachability.Result get()
    {
        return result;
    }

    public void set(Reachability.Result result)
    {
        this.result = result;
    }
}
