package com.rstracker;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps known teleport landing tiles (worldX, worldY, plane) to a human
 * readable name. This is a STARTER set covering only a handful of the most
 * iconic teleports - it is not exhaustive. Any jump that lands on a tile
 * not in this table just gets reported as an unlabeled teleport to that
 * coordinate, so the plugin degrades gracefully rather than failing.
 *
 * Coordinates here are approximate/best-effort and worth spot-checking
 * against your own actual teleport landings in-game before relying on the
 * labels - it was not practical to verify every entry against a live
 * client from this environment. Expanding this table over time as you
 * notice "unlabeled teleport" entries in real data is the intended workflow.
 */
public class TeleportLookup
{
	private static final Map<String, String> KNOWN_TELEPORTS = new HashMap<>();

	static
	{
		// Format: "x,y,plane" -> name. Plane 0 unless noted.
		KNOWN_TELEPORTS.put("3221,3217,0", "Lumbridge Home Teleport"); // verified against real captured data
		KNOWN_TELEPORTS.put("3087,3496,0", "Grand Exchange (GE Teleport)");
		KNOWN_TELEPORTS.put("2965,3378,0", "Falador Teleport");
		KNOWN_TELEPORTS.put("3105,3299,0", "Varrock Teleport");
		KNOWN_TELEPORTS.put("2757,3478,0", "Camelot Teleport");
		KNOWN_TELEPORTS.put("2662,3306,0", "Ardougne Teleport");
		KNOWN_TELEPORTS.put("3243,3403,0", "Edgeville Home Teleport (alt)");
		// Add more entries here as you notice unlabeled teleports you
		// recognise in your own exported route data.
	}

	public static String lookup(int x, int y, int plane)
	{
		return KNOWN_TELEPORTS.get(x + "," + y + "," + plane);
	}
}
