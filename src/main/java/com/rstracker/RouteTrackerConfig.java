package com.rstracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("routetracker")
public interface RouteTrackerConfig extends Config
{
	@ConfigItem(
		keyName = "teleportTileThreshold",
		name = "Teleport detection threshold",
		description = "Minimum tile distance between two ticks to be considered a teleport rather than walking/running."
	)
	default int teleportTileThreshold()
	{
		// Max run speed is 2 tiles/tick in a straight line. A few tiles of
		// slack avoids false positives from stairs/agility shortcuts etc.
		return 4;
	}

	@ConfigItem(
		keyName = "flushIntervalSeconds",
		name = "Local save interval (seconds)",
		description = "How often the tracked route is written to a local file while playing."
	)
	default int flushIntervalSeconds()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "githubRepo",
		name = "GitHub repo (optional)",
		description = "owner/repo to upload your route data to, e.g. lwr27/rs. Leave blank to keep everything local-only - nothing is ever sent anywhere unless both this and the token below are filled in.",
		warning = "Filling this in (along with the token below) will upload your route data - which includes your account hash and every location you visit - to the GitHub repository you specify, every time it saves locally. Only do this if you trust the destination repo and whoever it belongs to.",
		position = 3
	)
	default String githubRepo()
	{
		return "";
	}

	@ConfigItem(
		keyName = "githubToken",
		name = "GitHub token (optional)",
		description = "A GitHub Personal Access Token with permission to write to the repo above (needs the 'contents: write' permission, or classic 'repo' scope). Kept masked and stored locally only - never uploaded or shared anywhere except as the auth header on the upload request itself.",
		secret = true,
		position = 4
	)
	default String githubToken()
	{
		return "";
	}
}
