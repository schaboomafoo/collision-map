package com.example;

import com.collisionmap.CollisionMapPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class CollisionMapPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(CollisionMapPlugin.class);
		RuneLite.main(args);
	}
}
