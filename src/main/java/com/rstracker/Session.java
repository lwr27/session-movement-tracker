package com.rstracker;

import java.util.ArrayList;
import java.util.List;

/**
 * A play session: a contiguous stretch of play from login to logout,
 * grouping the events that happened during it. id is derived from the
 * session's own start time rather than a random UUID - simple, unique
 * enough per account file, and naturally sortable.
 *
 * end stays null while the session is still open/in-progress, or if the
 * client crashed/force-closed without a clean logout - RouteTrackerPlugin
 * uses that to decide whether to re-open (merge into) this session on the
 * next login rather than starting a brand new one.
 */
public class Session
{
	public String id;
	public long start;
	public Long end; // null = not cleanly closed yet
	public List<RouteEvent> events = new ArrayList<>();

	public static Session start(long startEpoch)
	{
		Session s = new Session();
		s.id = "s_" + startEpoch;
		s.start = startEpoch;
		return s;
	}
}
