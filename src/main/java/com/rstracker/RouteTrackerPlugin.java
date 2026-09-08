package com.rstracker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Tracks the player's route (walk segments, teleports, bank visits) grouped
 * into play sessions, written to a local JSON file. Optionally uploads the
 * same file to a GitHub repo the player configures themselves - fully
 * local-only unless both the repo and token config fields are filled in.
 */
@Slf4j
@PluginDescriptor(
	name = "Session Movement Tracker",
	description = "Records a session-by-session timeline of walking, teleports, bank visits, XP gains and hitpoints changes to a local file, with optional GitHub upload",
	tags = {"route", "tracker", "map", "location", "session", "movement", "xp", "hitpoints"}
)
public class RouteTrackerPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private RouteTrackerConfig config;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ScheduledExecutorService executor;

	@Provides
	RouteTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RouteTrackerConfig.class);
	}

	private WorldPoint lastTile;
	private WorldPoint walkSegmentStart;
	private long walkSegmentStartTime;
	private int idleTicks = 0;
	private boolean bankWasOpen = false;
	// Whether the walk segment in progress is inside an instance. Captured
	// at segment start; leaving or entering an instance always shows up as
	// a scene change and therefore a teleport, which closes the segment,
	// so it cannot change mid-segment.
	private boolean walkSegmentInstance = false;
	// Same idea for being aboard a boat (Sailing). Boarding and
	// disembarking both move the player between world views, which shows
	// up as a coordinate jump and closes the segment.
	private boolean walkSegmentBoat = false;

	// Intermediate turn points for the walk segment currently in progress,
	// stored flat as x,y,x,y,... - see recordMovement() for why only turns
	// are kept rather than every tile.
	private List<Integer> walkWaypoints = new ArrayList<>();
	// Run state (0/1) captured alongside each recorded point of the current
	// walk segment - index 0 is the segment start, then one per waypoint,
	// with the final point's state appended when the segment closes. Lets
	// the map colour a route by how it was actually travelled rather than
	// inferring speed from an averaged segment duration.
	private List<Integer> walkRunStates = new ArrayList<>();
	// Sign-normalised direction of the last movement (-1/0/1 per axis), so
	// walking and running (which covers 2 tiles per tick) both compare
	// equally - what matters is the heading, not the distance covered.
	private int lastDirX = 0;
	private int lastDirY = 0;
	// Run state as of the most recently processed movement tick, tracked
	// separately from walkRunStates - lets recordMovement() detect a
	// mid-straight-line run toggle (no direction change at all) as its own
	// trigger for a waypoint, not just turns.
	private int lastRunState = 0;

	private Session activeSession;
	private int ticksSinceFlush = 0;
	private int ticksSinceUpload = 0;
	// Baseline XP per skill, captured so each flush can record only what
	// changed since the previous one rather than absolute totals. Null
	// until the first StatChanged has been seen for that skill.
	private final Map<Skill, Integer> lastXpBySkill = new EnumMap<>(Skill.class);
	// Pending XP drops since the last flush, stored flat as
	// [offsetSeconds, skillIndex, amount, ...] where skillIndex points into
	// pendingXpSkills (the per-event legend of skill names). Emptied by
	// captureXpDeltas() on every flush.
	private final List<Integer> pendingXpDrops = new ArrayList<>();
	private final List<String> pendingXpSkills = new ArrayList<>();
	private long pendingXpStart = 0;
	private long pendingXpLast = 0;
	// Last tile seen before a login-type state change wiped lastTile. Lets
	// the first tick after a loading screen (boat trip, hop) record the
	// jump as a teleport instead of silently starting a fresh walk from
	// the new position.
	private WorldPoint lastKnownTile = null;
	// Hitpoints as of the last tick, or -1 before the first reading after
	// login. Compared every tick so only actual changes get recorded.
	private int lastHp = -1;
	// Pending hp changes since the last flush, stored flat as
	// [offsetSeconds, value, ...] relative to pendingHpStart. Emptied by
	// captureHpChanges() on every flush.
	private final List<Integer> pendingHpSamples = new ArrayList<>();
	private long pendingHpStart = 0;
	private long pendingHpLast = 0;
	// Guards against two uploads overlapping if one happens to still be in
	// flight (slow connection, GitHub API hiccup) when the next flush fires.
	private volatile boolean uploadInProgress = false;

	private static final int IDLE_TICKS_TO_CLOSE_SEGMENT = 3;
	private static final int TICK_MILLIS = 600;
	// If the last recorded event is within this many seconds of a new
	// login, treat it as a continuation of that same session rather than
	// starting a new one - this is what catches a crash/force-close
	// followed by promptly reopening the client.
	private static final long MERGE_GAP_SECONDS = 5 * 60;
	// Hard ceiling on waypoints per walk segment. Normal play never gets
	// near this (it's turns, not tiles), but it stops a pathological
	// session - e.g. hours of agility-course laps in one unbroken segment -
	// from growing a single event unboundedly.
	private static final int MAX_WAYPOINTS_PER_SEGMENT = 2000;
	// Hard ceiling on hp changes per flush cycle. Hitpoints can only change
	// once per tick, so at the default 60s save interval this is never
	// reached in normal play - it just bounds the event if someone sets a
	// very long save interval.
	private static final int MAX_HP_SAMPLES_PER_FLUSH = 500;
	// Hard ceiling on xp drops per flush cycle. Heavy combat is around
	// 50 drops a minute, so the default 60s save never gets near this.
	private static final int MAX_XP_DROPS_PER_FLUSH = 2000;
	private static final String GITHUB_UPLOAD_PATH_PREFIX = "docs/route-data/";

	private static final DateTimeFormatter MONTH_FMT =
		DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);
	// Uploaded files are now per-day rather than per-month: a month file
	// grows all month, so every upload late in the month re-sends a large
	// mostly-unchanged payload just to append one new session.
	private static final DateTimeFormatter DAY_FMT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
	// Matches the OLD flat local filename layout (<hash>-yyyy-MM.json sitting
	// directly in route-tracker/) so retention can clean those up too, not
	// just the per-month folders new saves use.
	private static final java.util.regex.Pattern LEGACY_FILE_PATTERN =
		java.util.regex.Pattern.compile("^-?\\d+-(\\d{4}-\\d{2})\\.json$");

	@Override
	protected void startUp()
	{
		lastTile = null;
		walkSegmentStart = null;
		idleTicks = 0;
		bankWasOpen = false;
		activeSession = null;
		ticksSinceFlush = 0;
		ticksSinceUpload = 0;
		lastXpBySkill.clear();
		resetXpTracking();
		resetHpTracking();
		lastKnownTile = null;
		resetWaypoints();
		pruneOldLocalData();
	}

	@Override
	protected void shutDown()
	{
		closeWalkSegmentIfAny();
		closeSessionCleanly();
		flushToDisk(true);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGING_IN || event.getGameState() == GameState.LOGIN_SCREEN)
		{
			closeWalkSegmentIfAny();
			closeSessionCleanly();
			flushToDisk(true);
			lastXpBySkill.clear();
			resetXpTracking();
			resetHpTracking();
			lastKnownTile = lastTile;
			lastTile = null;
			walkSegmentStart = null;
			idleTicks = 0;
			activeSession = null;
			resetWaypoints();
		}
	}

	/**
	 * Records each XP drop (skill, amount, second it landed) in memory.
	 * Nothing is written here; captureXpDeltas() packs everything since
	 * the last flush into a single event, so file writes stay per save
	 * while playback can still show drops at the moment they happened.
	 */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (!config.trackXpGains())
		{
			return;
		}

		Skill skill = event.getSkill();
		int xp = event.getXp();
		Integer previous = lastXpBySkill.put(skill, xp);
		// First sighting of a skill just establishes the baseline - the
		// jump from "unknown" to the account's lifetime total is not a gain.
		if (previous == null)
		{
			return;
		}
		int delta = xp - previous;
		if (delta <= 0 || pendingXpDrops.size() / 3 >= MAX_XP_DROPS_PER_FLUSH)
		{
			return;
		}
		long now = Instant.now().getEpochSecond();
		if (pendingXpDrops.isEmpty())
		{
			pendingXpStart = now;
		}
		String name = skill.getName();
		int idx = pendingXpSkills.indexOf(name);
		if (idx < 0)
		{
			pendingXpSkills.add(name);
			idx = pendingXpSkills.size() - 1;
		}
		pendingXpDrops.add((int) (now - pendingXpStart));
		pendingXpDrops.add(idx);
		pendingXpDrops.add(delta);
		pendingXpLast = now;
	}

	private void resetXpTracking()
	{
		pendingXpDrops.clear();
		pendingXpSkills.clear();
		pendingXpStart = 0;
		pendingXpLast = 0;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}

		long now = Instant.now().getEpochSecond();

		if (activeSession == null)
		{
			activeSession = resumeOrStartSession(now);
		}

		WorldPoint current = playerLocation(localPlayer);
		boolean instNow = inInstance();
		boolean boatNow = onBoat(localPlayer);
		checkBank(current, instNow, boatNow, now);

		if (lastTile == null)
		{
			// Coming back from a loading screen inside the same session
			// (boat trip, world hop): if the position moved while we
			// weren't looking, that's a teleport the plugin would
			// otherwise never record.
			if (lastKnownTile != null && !activeSession.events.isEmpty()
				&& (current.getPlane() != lastKnownTile.getPlane()
					|| current.distanceTo(lastKnownTile) > config.teleportTileThreshold()))
			{
				String label = TeleportLookup.lookup(current.getX(), current.getY(), current.getPlane());
				activeSession.events.add(tag(
					RouteEvent.teleport(toArr(lastKnownTile), toArr(current), label, now), instNow, boatNow));
			}
			lastKnownTile = null;
			lastTile = current;
			walkSegmentStart = current;
			walkSegmentStartTime = now;
			walkSegmentInstance = instNow;
			walkSegmentBoat = boatNow;
			idleTicks = 0;
			resetWaypoints();
		}
		else if (!current.equals(lastTile))
		{
			int distance = current.distanceTo(lastTile);

			if (current.getPlane() != lastTile.getPlane() || distance > config.teleportTileThreshold())
			{
				closeWalkSegmentIfAny();
				// Write out anything earned at the origin before the jump,
				// tagged with the origin, so XP and HP changes belong to
				// where they happened rather than wherever the next save
				// finds the player.
				captureXpDeltas(toArr(lastTile), walkSegmentInstance, walkSegmentBoat);
				captureHpChanges(toArr(lastTile), walkSegmentInstance, walkSegmentBoat);
				String label = TeleportLookup.lookup(current.getX(), current.getY(), current.getPlane());
				activeSession.events.add(tag(
					RouteEvent.teleport(toArr(lastTile), toArr(current), label, now), instNow, boatNow));
				walkSegmentStart = current;
				walkSegmentStartTime = now;
				walkSegmentInstance = instNow;
				walkSegmentBoat = boatNow;
				resetWaypoints();
			}
			else
			{
				recordMovement(current);
			}

			idleTicks = 0;
			lastTile = current;
		}
		else
		{
			idleTicks++;
			if (idleTicks == IDLE_TICKS_TO_CLOSE_SEGMENT)
			{
				closeWalkSegmentIfAny();
			}
		}

		sampleHp(now);
		maybeFlush();
	}

	/**
	 * Records the current hitpoints if (and only if) they differ from the
	 * previous tick. The first reading after login is treated as a change
	 * only when it is below max - starting a session at full health is the
	 * uninteresting default and writing it would just be noise, but
	 * starting already damaged is worth knowing.
	 */
	private void sampleHp(long now)
	{
		if (!config.trackHealth())
		{
			return;
		}

		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		if (hp <= 0 && lastHp == -1)
		{
			return; // stats not populated yet on this login
		}

		boolean changed;
		if (lastHp == -1)
		{
			changed = hp < client.getRealSkillLevel(Skill.HITPOINTS);
		}
		else
		{
			changed = hp != lastHp;
		}
		lastHp = hp;

		if (!changed || pendingHpSamples.size() / 2 >= MAX_HP_SAMPLES_PER_FLUSH)
		{
			return;
		}

		if (pendingHpSamples.isEmpty())
		{
			pendingHpStart = now;
		}
		pendingHpSamples.add((int) (now - pendingHpStart));
		pendingHpSamples.add(hp);
		pendingHpLast = now;
	}

	private void resetHpTracking()
	{
		lastHp = -1;
		pendingHpSamples.clear();
		pendingHpStart = 0;
		pendingHpLast = 0;
	}

	/**
	 * Emits the hp changes accumulated by sampleHp() since the last flush
	 * as a single event, then clears them. Does nothing when the feature
	 * is off or health never changed - so an hour at full hp adds no hp
	 * events at all.
	 */
	private void captureHpChanges()
	{
		int[] at = currentPositionOrLastKnown();
		Player lp = client.getLocalPlayer();
		captureHpChanges(at, inInstance(), lp != null && onBoat(lp));
	}

	private void captureHpChanges(int[] at, boolean inst, boolean boat)
	{
		if (!config.trackHealth() || pendingHpSamples.isEmpty() || activeSession == null || at == null)
		{
			return;
		}

		int[] samples = new int[pendingHpSamples.size()];
		for (int i = 0; i < samples.length; i++)
		{
			samples[i] = pendingHpSamples.get(i);
		}
		pendingHpSamples.clear();

		activeSession.events.add(tag(RouteEvent.hp(
			at, client.getRealSkillLevel(Skill.HITPOINTS),
			samples, pendingHpStart, pendingHpLast), inst, boat));
	}

	/**
	 * Called for ordinary (non-teleport) movement. Rather than storing every
	 * tile the player crosses - which would be mostly redundant, since a
	 * straight run is fully described by its two endpoints - this only
	 * records a waypoint when the direction of travel actually changes.
	 *
	 * The result is lossless for path *shape* (every corner is captured)
	 * while costing nothing extra for straight-line travel, so file size
	 * scales with how twisty a route was rather than how far it went.
	 */
	private void recordMovement(WorldPoint current)
	{
		int dirX = Integer.signum(current.getX() - lastTile.getX());
		int dirY = Integer.signum(current.getY() - lastTile.getY());

		boolean hadDirection = (lastDirX != 0 || lastDirY != 0);
		boolean turned = hadDirection && (dirX != lastDirX || dirY != lastDirY);

		// A run toggle mid-straight-line involves no direction change at
		// all, so on its own `turned` would never fire and the toggle
		// would silently fall between whatever turn-based waypoints happen
		// to exist either side of it. Treating a run-state change as its
		// own trigger - even without a turn - closes that gap.
		int runNow = currentRunState();
		boolean runChanged = runNow != lastRunState;

		if ((turned || runChanged) && walkWaypoints.size() / 2 < MAX_WAYPOINTS_PER_SEGMENT)
		{
			// The change happened AT lastTile - that's the corner (or the
			// point run was toggled) worth keeping, not the tile we've
			// just arrived at.
			walkWaypoints.add(lastTile.getX());
			walkWaypoints.add(lastTile.getY());
			walkRunStates.add(runNow);
		}

		lastDirX = dirX;
		lastDirY = dirY;
		lastRunState = runNow;
	}

	private void resetWaypoints()
	{
		walkWaypoints.clear();
		walkRunStates.clear();
		// Seeded with the state at the segment's start point, so the run
		// state list always lines up 1:1 with the segment's implied point
		// list (start, waypoints..., end). lastRunState mirrors it so the
		// very first movement tick of a new segment isn't wrongly treated
		// as a run-state change against a stale value from the previous
		// segment.
		int startRunState = currentRunState();
		walkRunStates.add(startRunState);
		lastRunState = startRunState;
		lastDirX = 0;
		lastDirY = 0;
	}

	/**
	 * 1 if run is currently enabled, 0 otherwise. Read from the same
	 * varplayer the client's own run orb uses.
	 */
	private int currentRunState()
	{
		return client.getVarpValue(VarPlayerID.OPTION_RUN) == 1 ? 1 : 0;
	}

	private void checkBank(WorldPoint current, boolean inst, boolean boat, long now)
	{
		boolean bankOpenNow = client.getWidget(InterfaceID.Bankmain.UNIVERSE) != null;
		if (bankOpenNow && !bankWasOpen)
		{
			activeSession.events.add(tag(RouteEvent.bank(toArr(current), now), inst, boat));
		}
		bankWasOpen = bankOpenNow;
	}

	/**
	 * The player's tile. Inside an instance this is translated to the
	 * instance's template coordinates (where that piece of map lives in
	 * the real world) so it can still be drawn. Everywhere else it is the
	 * plain world location, exactly as recorded before instance support
	 * was added - deliberately not routed through fromLocalInstance, whose
	 * non-instance path resolves against the top-level world view and so
	 * might differ from getWorldLocation() while aboard a boat.
	 */
	private WorldPoint playerLocation(Player localPlayer)
	{
		if (inInstance())
		{
			WorldPoint template = WorldPoint.fromLocalInstance(client, localPlayer.getLocalLocation());
			if (template != null)
			{
				return template;
			}
		}
		return localPlayer.getWorldLocation();
	}

	private boolean inInstance()
	{
		WorldView wv = client.getTopLevelWorldView();
		return wv != null && wv.isInstance();
	}

	/**
	 * Aboard a boat, the player belongs to the boat's own world view
	 * rather than the top-level one.
	 */
	private static boolean onBoat(Player localPlayer)
	{
		WorldView wv = localPlayer.getWorldView();
		return wv != null && !wv.isTopLevel();
	}

	private static RouteEvent tag(RouteEvent ev, boolean inst, boolean boat)
	{
		ev.i = inst ? 1 : null;
		ev.b = boat ? 1 : null;
		return ev;
	}

	private void closeWalkSegmentIfAny()
	{
		if (activeSession != null && walkSegmentStart != null && lastTile != null
			&& !walkSegmentStart.equals(lastTile))
		{
			// Final point's run state completes the list before it's frozen
			// into the event - see RouteEvent.walk for how a constant state
			// across the whole segment collapses to a single value.
			walkRunStates.add(currentRunState());
			activeSession.events.add(tag(RouteEvent.walk(
				toArr(walkSegmentStart), toArr(lastTile), waypointsToArr(),
				runStatesToArr(), walkSegmentStartTime, Instant.now().getEpochSecond()),
				walkSegmentInstance, walkSegmentBoat));
		}
		walkSegmentStart = lastTile;
		walkSegmentStartTime = Instant.now().getEpochSecond();
		walkSegmentInstance = inInstance();
		Player lp = client.getLocalPlayer();
		walkSegmentBoat = lp != null && onBoat(lp);
		resetWaypoints();
	}

	private int[] waypointsToArr()
	{
		if (walkWaypoints.isEmpty())
		{
			return null;
		}
		int[] arr = new int[walkWaypoints.size()];
		for (int i = 0; i < walkWaypoints.size(); i++)
		{
			arr[i] = walkWaypoints.get(i);
		}
		return arr;
	}

	private int[] runStatesToArr()
	{
		if (walkRunStates.isEmpty())
		{
			return null;
		}
		int[] arr = new int[walkRunStates.size()];
		for (int i = 0; i < walkRunStates.size(); i++)
		{
			arr[i] = walkRunStates.get(i);
		}
		return arr;
	}

	/**
	 * Emits everything accumulated by onStatChanged since the last flush as
	 * a single event tagged with the player's current position, then clears
	 * the accumulator. Does nothing when the feature is off or no XP was
	 * gained - so a session with no training adds no xp events at all.
	 */
	private void captureXpDeltas()
	{
		int[] at = currentPositionOrLastKnown();
		Player lp = client.getLocalPlayer();
		captureXpDeltas(at, inInstance(), lp != null && onBoat(lp));
	}

	private void captureXpDeltas(int[] at, boolean inst, boolean boat)
	{
		if (!config.trackXpGains() || pendingXpDrops.isEmpty() || activeSession == null || at == null)
		{
			return;
		}

		int[] drops = new int[pendingXpDrops.size()];
		for (int i = 0; i < drops.length; i++)
		{
			drops[i] = pendingXpDrops.get(i);
		}
		String[] skills = pendingXpSkills.toArray(new String[0]);
		long start = pendingXpStart, end = pendingXpLast;
		resetXpTracking();

		activeSession.events.add(tag(RouteEvent.xp(at, skills, drops, start, end), inst, boat));
	}

	/**
	 * The player's current tile, falling back to the last tile seen on a
	 * game tick. The local player can already be gone by the time the
	 * logout flush runs, and without the fallback everything accumulated
	 * since the previous save would be silently dropped.
	 */
	private int[] currentPositionOrLastKnown()
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer != null && localPlayer.getLocalLocation() != null)
		{
			return toArr(playerLocation(localPlayer));
		}
		return lastTile != null ? toArr(lastTile) : null;
	}

	private void closeSessionCleanly()
	{
		if (activeSession != null)
		{
			activeSession.end = Instant.now().getEpochSecond();
		}
	}

	/**
	 * Looks for an existing, not-cleanly-closed (or recently-closed)
	 * session in this month's file and resumes it if the gap is small
	 * enough - otherwise starts a fresh session.
	 */
	private Session resumeOrStartSession(long now)
	{
		File file = dataFile(now);
		List<Session> existing = readSessions(file);

		if (!existing.isEmpty())
		{
			Session last = existing.get(existing.size() - 1);
			long lastActivity = lastEventTime(last);
			if (now - lastActivity <= MERGE_GAP_SECONDS)
			{
				last.end = null; // reopen it
				return last;
			}
		}

		return Session.start(now);
	}

	private long lastEventTime(Session s)
	{
		long latest = s.start;
		for (RouteEvent ev : s.events)
		{
			long t = ev.e != null ? ev.e : ev.s;
			if (t > latest)
			{
				latest = t;
			}
		}
		return s.end != null ? Math.max(latest, s.end) : latest;
	}

	/**
	 * Local saves and GitHub uploads run on independent timers. Saving is
	 * cheap and frequent (crash safety); uploading creates a commit every
	 * time, so doing it as often as saving would generate a large amount of
	 * unnecessary commit history for no benefit - uploaded data is only
	 * ever read back retrospectively, never watched live.
	 */
	private void maybeFlush()
	{
		ticksSinceFlush++;
		ticksSinceUpload++;

		int flushEveryTicks = Math.max(1, (config.flushIntervalSeconds() * 1000) / TICK_MILLIS);
		int uploadEveryTicks = Math.max(1, (config.uploadIntervalSeconds() * 1000) / TICK_MILLIS);

		boolean shouldUpload = ticksSinceUpload >= uploadEveryTicks;
		if (ticksSinceFlush >= flushEveryTicks || shouldUpload)
		{
			flushToDisk(shouldUpload);
			ticksSinceFlush = 0;
			if (shouldUpload)
			{
				ticksSinceUpload = 0;
			}
		}
	}

	/**
	 * Base plugin directory. Uses RuneLite's own RUNELITE_DIR rather than
	 * System.getProperty("user.home") directly - RUNELITE_DIR is what
	 * RuneLite itself resolves the client's actual .runelite directory to
	 * (correctly handling custom/portable install locations), so plugins
	 * should key off it rather than re-deriving user.home themselves.
	 */
	private File baseDir()
	{
		File dir = new File(RuneLite.RUNELITE_DIR, "route-tracker");
		dir.mkdirs();
		return dir;
	}

	/**
	 * Local data is organised as route-tracker/&lt;yyyy-MM&gt;/&lt;hash&gt;-&lt;yyyy-MM-dd&gt;.json
	 * - one file per account per day, inside a folder per month. The folder
	 * layout is what makes retention simple: an expired month is one
	 * directory to delete rather than a filename-matching sweep.
	 */
	private File dataFile(long epochSeconds)
	{
		String accountKey = String.valueOf(client.getAccountHash());
		Instant instant = Instant.ofEpochSecond(epochSeconds);
		File monthDir = new File(baseDir(), MONTH_FMT.format(instant));
		monthDir.mkdirs();
		return new File(monthDir, accountKey + "-" + DAY_FMT.format(instant) + ".json");
	}

	/**
	 * Deletes local data older than the configured retention window.
	 * Handles BOTH layouts: whole month folders from the current layout,
	 * and leftover flat &lt;hash&gt;-yyyy-MM.json files from the older one, so
	 * upgrading doesn't leave old data stranded and never cleaned up.
	 *
	 * Only ever touches local files - anything already uploaded to GitHub
	 * is deliberately left alone.
	 */
	private void pruneOldLocalData()
	{
		int months = config.retentionMonths();
		if (months <= 0)
		{
			return; // 0 (or negative) means keep everything forever
		}

		String cutoff = MONTH_FMT.format(
			Instant.now().atZone(ZoneOffset.UTC).minusMonths(months).toInstant());

		File[] entries = baseDir().listFiles();
		if (entries == null)
		{
			return;
		}

		for (File entry : entries)
		{
			try
			{
				String expiredMonth = null;
				if (entry.isDirectory() && entry.getName().matches("\\d{4}-\\d{2}"))
				{
					expiredMonth = entry.getName();
				}
				else if (entry.isFile())
				{
					java.util.regex.Matcher m = LEGACY_FILE_PATTERN.matcher(entry.getName());
					if (m.matches())
					{
						expiredMonth = m.group(1);
					}
				}

				// String comparison is safe here: yyyy-MM sorts
				// chronologically as text.
				if (expiredMonth != null && expiredMonth.compareTo(cutoff) < 0)
				{
					deleteRecursively(entry);
					log.debug("Pruned expired route data: {}", entry.getName());
				}
			}
			catch (Exception e)
			{
				log.warn("Could not prune route data entry {}", entry.getName(), e);
			}
		}
	}

	private void deleteRecursively(File file)
	{
		if (file.isDirectory())
		{
			File[] children = file.listFiles();
			if (children != null)
			{
				for (File child : children)
				{
					deleteRecursively(child);
				}
			}
		}
		if (!file.delete())
		{
			log.debug("Could not delete {}", file.getAbsolutePath());
		}
	}

	private List<Session> readSessions(File file)
	{
		List<Session> result = new ArrayList<>();
		if (!file.exists())
		{
			return result;
		}
		try (FileReader reader = new FileReader(file))
		{
			Type listType = new TypeToken<List<Session>>() {}.getType();
			List<Session> fromDisk = gson.fromJson(reader, listType);
			if (fromDisk != null)
			{
				result.addAll(fromDisk);
			}
		}
		catch (Exception e)
		{
			log.warn("Could not read existing route file {}, starting fresh", file.getName(), e);
		}
		return result;
	}

	private synchronized void flushToDisk(boolean alsoUpload)
	{
		// Fold in anything accumulated since the last flush before deciding
		// whether there's data worth writing - during stationary training
		// this is the only thing that produces events at all.
		captureXpDeltas();
		captureHpChanges();

		if (activeSession == null || activeSession.events.isEmpty())
		{
			return;
		}

		try
		{
			File file = dataFile(activeSession.start);
			List<Session> existing = readSessions(file);

			// Replace any existing on-disk copy of this same session with
			// the current in-memory version (which has everything so far).
			existing.removeIf(s -> s.id.equals(activeSession.id));
			existing.add(activeSession);

			try (FileWriter writer = new FileWriter(file))
			{
				gson.toJson(existing, writer);
			}

			if (alsoUpload)
			{
				maybeUploadToGitHub(file);
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to flush route data to disk", e);
		}
	}

	/**
	 * Uploads the just-written local file to the player's configured GitHub
	 * repo, if (and only if) both the repo and token config fields are
	 * filled in - fully optional, fully local-only otherwise. Runs on a
	 * background thread (never the game/client thread) so a slow network
	 * or GitHub API hiccup can never cause a client freeze.
	 */
	private void maybeUploadToGitHub(File file)
	{
		if (!config.enableGithubUpload())
		{
			return; // opt-in toggle is off - no network call happens, full stop
		}

		String repo = config.githubRepo() == null ? "" : config.githubRepo().trim();
		String token = config.githubToken() == null ? "" : config.githubToken().trim();

		if (repo.isEmpty() || token.isEmpty())
		{
			return; // local-only - nothing configured
		}

		if (uploadInProgress)
		{
			log.debug("Skipping GitHub upload - previous upload still in progress");
			return;
		}

		uploadInProgress = true;
		executor.execute(() ->
		{
			try
			{
				uploadFileToGitHub(file, repo, token);
			}
			catch (Exception e)
			{
				log.warn("GitHub upload failed", e);
			}
			finally
			{
				uploadInProgress = false;
			}
		});
	}

	private void uploadFileToGitHub(File file, String repo, String token) throws Exception
	{
		if (!config.enableGithubUpload())
		{
			return; // belt-and-braces check - this method makes an OkHttp call itself
		}

		String path = GITHUB_UPLOAD_PATH_PREFIX + uploadRelativePath(file.getName());
		String apiUrl = "https://api.github.com/repos/" + repo + "/contents/" + path;

		// GitHub's Contents API requires the CURRENT file's sha to update an
		// existing file (and rejects the request without one, to prevent
		// accidentally clobbering someone else's concurrent edit) - omitted
		// entirely for a brand new file, which the 404 case below signals.
		// The same call returns the remote file's content, which is merged
		// with the local copy so that playing the same account from two
		// computers on one day doesn't have the second machine's upload
		// wipe out the first's sessions.
		RemoteFile remote = fetchRemoteFile(apiUrl, token);
		String existingSha = remote != null ? remote.sha : null;

		byte[] contentBytes = Files.readAllBytes(file.toPath());
		if (remote != null && remote.content != null)
		{
			byte[] merged = mergeSessionFiles(remote.content, contentBytes);
			if (merged != null)
			{
				contentBytes = merged;
			}
		}
		String base64Content = Base64.getEncoder().encodeToString(contentBytes);

		JsonObject body = new JsonObject();
		body.addProperty("message", "Update route data: " + file.getName());
		body.addProperty("content", base64Content);
		if (existingSha != null)
		{
			body.addProperty("sha", existingSha);
		}

		Request request = new Request.Builder()
			.url(apiUrl)
			.header("Authorization", "token " + token)
			.header("Accept", "application/vnd.github+json")
			.put(RequestBody.create(MediaType.parse("application/json"), body.toString()))
			.build();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				String responseBody = response.body() != null ? response.body().string() : "";
				log.warn("GitHub upload of {} failed: {} {} - {}", file.getName(), response.code(), response.message(), responseBody);
			}
			else
			{
				log.debug("Uploaded {} to {}", file.getName(), apiUrl);
			}
		}
	}

	/**
	 * Remote layout is one folder per account: &lt;hash&gt;/&lt;yyyy-MM-dd&gt;.json.
	 * The hash is taken from the local filename rather than asked of the
	 * client, because this runs on a background thread and may run during
	 * the logout flush, when client state is no longer reliable. GitHub's
	 * Contents API creates the folder on first upload, so nothing needs
	 * setting up on the repo side.
	 */
	private static String uploadRelativePath(String localName)
	{
		// Local name is <hash>-yyyy-MM-dd.json; the hash itself can be
		// negative, so split at the dash that precedes the date part
		// rather than the first dash in the string.
		int dateLen = "yyyy-MM-dd.json".length();
		if (localName.length() > dateLen + 1 && localName.charAt(localName.length() - dateLen - 1) == '-')
		{
			String hash = localName.substring(0, localName.length() - dateLen - 1);
			String dayFile = localName.substring(localName.length() - dateLen);
			return hash + "/" + dayFile;
		}
		return localName; // unexpected name shape - fall back to flat layout
	}

	private static final class RemoteFile
	{
		final String sha;
		final byte[] content;

		RemoteFile(String sha, byte[] content)
		{
			this.sha = sha;
			this.content = content;
		}
	}

	/**
	 * Merges the remote copy of a day file with the local one: sessions
	 * are keyed by id, the local version wins for any id present in both
	 * (it's the live in-memory one), and anything only on the remote side
	 * - sessions recorded on another computer - is kept. Result is sorted
	 * by session start. Returns null if the remote file can't be parsed,
	 * in which case the caller uploads the local file as-is.
	 */
	private byte[] mergeSessionFiles(byte[] remoteBytes, byte[] localBytes)
	{
		try
		{
			Type listType = new TypeToken<List<Session>>() {}.getType();
			List<Session> remote = gson.fromJson(new String(remoteBytes, StandardCharsets.UTF_8), listType);
			List<Session> local = gson.fromJson(new String(localBytes, StandardCharsets.UTF_8), listType);
			if (remote == null || remote.isEmpty())
			{
				return null;
			}
			Map<String, Session> byId = new LinkedHashMap<>();
			for (Session s : remote)
			{
				if (s != null && s.id != null)
				{
					byId.put(s.id, s);
				}
			}
			if (local != null)
			{
				for (Session s : local)
				{
					if (s != null && s.id != null)
					{
						byId.put(s.id, s);
					}
				}
			}
			List<Session> merged = new ArrayList<>(byId.values());
			merged.sort((a, b) -> Long.compare(a.start, b.start));
			return gson.toJson(merged, listType).getBytes(StandardCharsets.UTF_8);
		}
		catch (Exception e)
		{
			log.debug("Could not merge remote route file, uploading local copy as-is", e);
			return null;
		}
	}

	/**
	 * Fetches the current sha and content of the remote file, or null if
	 * it doesn't exist there yet (a fresh upload, e.g. a new day's file).
	 */
	private RemoteFile fetchRemoteFile(String apiUrl, String token)
	{
		if (!config.enableGithubUpload())
		{
			return null; // belt-and-braces check - this method makes an OkHttp call itself
		}

		Request request = new Request.Builder()
			.url(apiUrl)
			.header("Authorization", "token " + token)
			.header("Accept", "application/vnd.github+json")
			.get()
			.build();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (response.code() == 404)
			{
				return null; // doesn't exist remotely yet - fine, this is a new file
			}
			if (!response.isSuccessful() || response.body() == null)
			{
				log.warn("Could not check existing GitHub file (status {}) - upload will be attempted without a sha and may fail if the file already exists", response.code());
				return null;
			}
			JsonObject obj = new JsonParser().parse(response.body().string()).getAsJsonObject();
			String sha = obj.has("sha") ? obj.get("sha").getAsString() : null;
			byte[] content = null;
			// Content comes back base64 with embedded newlines; the MIME
			// decoder tolerates those where the basic one rejects them.
			if (obj.has("content") && !obj.get("content").isJsonNull())
			{
				try
				{
					content = Base64.getMimeDecoder().decode(obj.get("content").getAsString());
				}
				catch (IllegalArgumentException e)
				{
					log.debug("Could not decode remote route file content", e);
				}
			}
			return sha != null ? new RemoteFile(sha, content) : null;
		}
		catch (Exception e)
		{
			log.warn("Failed to check existing GitHub file sha", e);
			return null;
		}
	}

	private static int[] toArr(WorldPoint p)
	{
		return new int[] { p.getX(), p.getY(), p.getPlane() };
	}
}
