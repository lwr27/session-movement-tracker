package com.rstracker;

/**
 * A single route event, using short field names to keep the JSON compact:
 *   ty = type ("walk" | "tp" | "bank")
 *   f  = from [x,y,plane]
 *   t  = to [x,y,plane]
 *   w  = walk waypoints, flat [x1,y1,x2,y2,...] - walk only, omitted when
 *        the walk was a straight line with no turns (the common case)
 *   s  = start time (epoch seconds)
 *   e  = end time (epoch seconds) - walk only
 *   lbl = teleport label, if matched against TeleportLookup - tp only
 *
 * Waypoints deliberately store only x/y, not plane: a plane change is
 * already treated as a teleport by RouteTrackerPlugin and closes the walk
 * segment, so plane is constant for the whole of any single walk event and
 * storing it per-point would just be repeated bytes.
 *
 * Waypoints are recorded only at direction changes (see the plugin), not
 * once per tick - so a long straight run costs nothing extra, while a
 * winding path costs one point per turn. File size therefore scales with
 * how twisty the route was, not how far it went.
 */
public class RouteEvent
{
	public String ty;
	public int[] f;
	public int[] t;
	public int[] w; // null so Gson omits it entirely for straight walks / non-walk events
	public long s;
	public Long e; // boxed so it's omitted from JSON when null (non-walk events)
	public String lbl;

	public static RouteEvent walk(int[] from, int[] to, int[] waypoints, long start, long end)
	{
		RouteEvent ev = new RouteEvent();
		ev.ty = "walk";
		ev.f = from;
		ev.t = to;
		ev.w = (waypoints != null && waypoints.length > 0) ? waypoints : null;
		ev.s = start;
		ev.e = end;
		return ev;
	}

	public static RouteEvent teleport(int[] from, int[] to, String label, long time)
	{
		RouteEvent ev = new RouteEvent();
		ev.ty = "tp";
		ev.f = from;
		ev.t = to;
		ev.s = time;
		ev.lbl = label;
		return ev;
	}

	public static RouteEvent bank(int[] at, long time)
	{
		RouteEvent ev = new RouteEvent();
		ev.ty = "bank";
		ev.t = at;
		ev.s = time;
		return ev;
	}
}
