package com.rstracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Run this class's main() from IntelliJ (right-click -> Run) to launch the
 * full RuneLite client from source with this plugin loaded and active.
 * This is the standard way to test a plugin locally without publishing it
 * anywhere - it is not a separate lightweight tool, it boots the real
 * client.
 */
public class RouteTrackerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(RouteTrackerPlugin.class);
		RuneLite.main(args);
	}
}
