package com.rstracker;

import java.util.Map;

/**
 * A single route event, using short field names to keep the JSON compact:
 *   ty = type ("walk" | "tp" | "bank" | "xp")
 *   f  = from [x,y,plane]
 *   t  = to [x,y,plane]
 *   w  = walk waypoints, flat [x1,y1,x2,y2,...] - walk only, omitted when
 *        the walk was a straight line with no turns (the common case)
 *   r  = run state, flat [0|1, ...] - walk only, one entry per point in the
 *        implied point list (from, then each waypoint, then to), recording
 *        whether run was enabled on arrival at that point. Omitted entirely
 *        when run state never changed across the whole segment (the common
 *        case), in which case rAll below carries the single value instead.
 *   rAll = run state for the whole segment when it never changed - walk
 *        only, omitted when `r` is present
 *   s  = start time (epoch seconds)
 *   e  = end time (epoch seconds) - walk only
 *   lbl = teleport label, if matched against TeleportLookup - tp only
 *   xp = per-skill XP gained since the previous xp event - xp only
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
	public int[] r; // null so Gson omits it when run state was constant (see rAll)
	public Integer rAll; // boxed so Gson omits it when per-point run state is stored in r
	public long s;
	public Long e; // boxed so it's omitted from JSON when null (non-walk events)
	public String lbl;
	public Map<String, Integer> xp; // null so Gson omits it for non-xp events

	public static RouteEvent walk(int[] from, int[] to, int[] waypoints,
		int[] runStates, long start, long end)
	{
		RouteEvent ev = new RouteEvent();
		ev.ty = "walk";
		ev.f = from;
		ev.t = to;
		ev.w = (waypoints != null && waypoints.length > 0) ? waypoints : null;
		ev.s = start;
		ev.e = end;

		// Run state is almost always constant for a whole segment (you
		// rarely toggle run mid-walk), so collapse it to a single value in
		// that case rather than storing an identical entry per point.
		if (runStates != null && runStates.length > 0)
		{
			boolean allSame = true;
			for (int i = 1; i < runStates.length; i++)
			{
				if (runStates[i] != runStates[0])
				{
					allSame = false;
					break;
				}
			}
			if (allSame)
			{
				ev.rAll = runStates[0];
			}
			else
			{
				ev.r = runStates;
			}
		}
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

	/**
	 * XP gained since the previous xp event, tagged with where the player
	 * was when it was recorded. Batched once per flush cycle rather than
	 * per XP drop - see RouteTrackerPlugin.captureXpDeltas().
	 */
	public static RouteEvent xp(int[] at, Map<String, Integer> gains, long time)
	{
		RouteEvent ev = new RouteEvent();
		ev.ty = "xp";
		ev.t = at;
		ev.xp = gains;
		ev.s = time;
		return ev;
	}
}
