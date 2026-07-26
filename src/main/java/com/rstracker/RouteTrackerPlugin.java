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
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.WidgetInfo;
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
	description = "Records a session-by-session timeline of walking, teleports, and bank visits to a local file, with optional GitHub upload",
	tags = {"route", "tracker", "map", "location", "session", "movement"}
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
	// Sign-normalised direction of the last movement (-1/0/1 per axis), so
	// walking and running (which covers 2 tiles per tick) both compare
	// equally - what matters is the heading, not the distance covered.
	private int lastDirX = 0;
	private int lastDirY = 0;

	private Session activeSession;
	private int ticksSinceFlush = 0;
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
	private static final String GITHUB_UPLOAD_PATH_PREFIX = "docs/route-data/";

	private static final DateTimeFormatter MONTH_FMT =
		DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

	@Override
	protected void startUp()
	{
		lastTile = null;
		walkSegmentStart = null;
		idleTicks = 0;
		bankWasOpen = false;
		activeSession = null;
		ticksSinceFlush = 0;
		resetWaypoints();
	}

	@Override
	protected void shutDown()
	{
		closeWalkSegmentIfAny();
		closeSessionCleanly();
		flushToDisk();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGING_IN || event.getGameState() == GameState.LOGIN_SCREEN)
		{
			closeWalkSegmentIfAny();
			closeSessionCleanly();
			flushToDisk();
			lastTile = null;
			walkSegmentStart = null;
			idleTicks = 0;
			activeSession = null;
			resetWaypoints();
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

		maybeFlush();
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

		if (turned && walkWaypoints.size() / 2 < MAX_WAYPOINTS_PER_SEGMENT)
		{
			// The turn happened AT lastTile - that's the corner worth
			// keeping, not the tile we've just arrived at.
			walkWaypoints.add(lastTile.getX());
			walkWaypoints.add(lastTile.getY());
		}

		lastDirX = dirX;
		lastDirY = dirY;
	}

	private void resetWaypoints()
	{
		walkWaypoints.clear();
		lastDirX = 0;
		lastDirY = 0;
	}

	private void checkBank(WorldPoint current, long now)
	{
		boolean bankOpenNow = client.getWidget(WidgetInfo.BANK_CONTAINER) != null;
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
			activeSession.events.add(RouteEvent.walk(
				toArr(walkSegmentStart), toArr(lastTile), waypointsToArr(),
				walkSegmentStartTime, Instant.now().getEpochSecond()));
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

	private void maybeFlush()
	{
		ticksSinceFlush++;
		int flushEveryTicks = Math.max(1, (config.flushIntervalSeconds() * 1000) / TICK_MILLIS);
		if (ticksSinceFlush >= flushEveryTicks)
		{
			flushToDisk();
			ticksSinceFlush = 0;
		}
	}

	private File dataFile(long epochSeconds)
	{
		String accountKey = String.valueOf(client.getAccountHash());
		String month = MONTH_FMT.format(Instant.ofEpochSecond(epochSeconds));
		File dir = new File(System.getProperty("user.home"), ".runelite/route-tracker");
		dir.mkdirs();
		return new File(dir, accountKey + "-" + month + ".json");
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

	private synchronized void flushToDisk()
	{
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

			maybeUploadToGitHub(file);
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
