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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
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
	private final Map<Skill, Integer> pendingXpGains = new EnumMap<>(Skill.class);
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
		pendingXpGains.clear();
		resetHpTracking();
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
			pendingXpGains.clear();
			resetHpTracking();
			lastTile = null;
			walkSegmentStart = null;
			idleTicks = 0;
			activeSession = null;
			resetWaypoints();
		}
	}

	/**
	 * Accumulates XP gains between flushes. RuneLite fires StatChanged very
	 * frequently (potentially several times a second while training), so
	 * nothing is written here - gains are just totalled up in memory and
	 * emitted as a single event per flush cycle by captureXpDeltas().
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
		if (delta > 0)
		{
			pendingXpGains.merge(skill, delta, Integer::sum);
		}
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

		WorldPoint current = localPlayer.getWorldLocation();
		checkBank(current, now);

		if (lastTile == null)
		{
			lastTile = current;
			walkSegmentStart = current;
			walkSegmentStartTime = now;
			idleTicks = 0;
			resetWaypoints();
		}
		else if (!current.equals(lastTile))
		{
			int distance = current.distanceTo(lastTile);

			if (current.getPlane() != lastTile.getPlane() || distance > config.teleportTileThreshold())
			{
				closeWalkSegmentIfAny();
				String label = TeleportLookup.lookup(current.getX(), current.getY(), current.getPlane());
				activeSession.events.add(RouteEvent.teleport(toArr(lastTile), toArr(current), label, now));
				walkSegmentStart = current;
				walkSegmentStartTime = now;
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
		if (!config.trackHealth() || pendingHpSamples.isEmpty() || activeSession == null)
		{
			return;
		}
		int[] at = currentPositionOrLastKnown();
		if (at == null)
		{
			return;
		}

		int[] samples = new int[pendingHpSamples.size()];
		for (int i = 0; i < samples.length; i++)
		{
			samples[i] = pendingHpSamples.get(i);
		}
		pendingHpSamples.clear();

		activeSession.events.add(RouteEvent.hp(
			at, client.getRealSkillLevel(Skill.HITPOINTS),
			samples, pendingHpStart, pendingHpLast));
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

	private void checkBank(WorldPoint current, long now)
	{
		boolean bankOpenNow = client.getWidget(InterfaceID.Bankmain.UNIVERSE) != null;
		if (bankOpenNow && !bankWasOpen)
		{
			activeSession.events.add(RouteEvent.bank(toArr(current), now));
		}
		bankWasOpen = bankOpenNow;
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
			activeSession.events.add(RouteEvent.walk(
				toArr(walkSegmentStart), toArr(lastTile), waypointsToArr(),
				runStatesToArr(), walkSegmentStartTime, Instant.now().getEpochSecond()));
		}
		walkSegmentStart = lastTile;
		walkSegmentStartTime = Instant.now().getEpochSecond();
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
		if (!config.trackXpGains() || pendingXpGains.isEmpty() || activeSession == null)
		{
			return;
		}
		int[] at = currentPositionOrLastKnown();
		if (at == null)
		{
			return;
		}

		Map<String, Integer> gains = new HashMap<>();
		pendingXpGains.forEach((skill, amount) -> gains.put(skill.getName(), amount));
		pendingXpGains.clear();

		activeSession.events.add(RouteEvent.xp(at, gains, Instant.now().getEpochSecond()));
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
		if (localPlayer != null && localPlayer.getWorldLocation() != null)
		{
			return toArr(localPlayer.getWorldLocation());
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

		byte[] contentBytes = Files.readAllBytes(file.toPath());
		String base64Content = Base64.getEncoder().encodeToString(contentBytes);
		String path = GITHUB_UPLOAD_PATH_PREFIX + file.getName();
		String apiUrl = "https://api.github.com/repos/" + repo + "/contents/" + path;

		// GitHub's Contents API requires the CURRENT file's sha to update an
		// existing file (and rejects the request without one, to prevent
		// accidentally clobbering someone else's concurrent edit) - omitted
		// entirely for a brand new file, which the 404 case below signals.
		String existingSha = fetchExistingSha(apiUrl, token);

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
	 * Fetches the current sha of the remote file, or null if it doesn't
	 * exist there yet (a fresh upload, e.g. a new month's file).
	 */
	private String fetchExistingSha(String apiUrl, String token)
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
			return obj.has("sha") ? obj.get("sha").getAsString() : null;
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
