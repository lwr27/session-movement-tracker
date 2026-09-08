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
		description = "Minimum tile distance between two ticks to be considered a teleport rather than walking/running.",
		position = 1
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
		description = "How often the tracked route is written to a local file while playing.",
		position = 2
	)
	default int flushIntervalSeconds()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "trackXpGains",
		name = "Track XP gains",
		description = "Records which skills gained XP and how much, batched once per local save rather than per XP drop. Adds a small amount of data per save, and is the only thing recorded at all during stationary training (where there is no movement to track).",
		position = 3
	)
	default boolean trackXpGains()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackHealth",
		name = "Track hitpoints",
		description = "Records changes to your current hitpoints, batched once per local save. Only changes are stored, so a session spent at full health writes nothing at all - data is only added while taking damage or healing.",
		position = 4
	)
	default boolean trackHealth()
	{
		return true;
	}

	@ConfigItem(
		keyName = "retentionMonths",
		name = "Keep local data for (months)",
		description = "Local route data older than this many months is deleted automatically. Set to 0 to keep everything forever. Only affects files on this computer - anything already uploaded to GitHub is never deleted by this plugin.",
		position = 5
	)
	default int retentionMonths()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "enableGithubUpload",
		name = "Enable GitHub upload",
		description = "Must be turned on, together with filling in the repo and token below, for any network upload to happen. Off by default - with this disabled, everything stays fully local no matter what is in the fields below.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by Runelite developers.",
		position = 6
	)
	default boolean enableGithubUpload()
	{
		return false;
	}

	@ConfigItem(
		keyName = "uploadIntervalSeconds",
		name = "GitHub upload interval (seconds)",
		description = "How often data is uploaded to GitHub, when uploads are enabled. Deliberately separate from (and normally longer than) the local save interval - local saves are frequent for crash safety, but every upload creates a commit, so uploading as often as saving generates a lot of unnecessary commit history.",
		position = 7
	)
	default int uploadIntervalSeconds()
	{
		return 300;
	}

	@ConfigItem(
		keyName = "githubRepo",
		name = "GitHub repo (optional)",
		description = "owner/repo to upload your route data to, e.g. lwr27/rs. Leave blank to keep everything local-only - nothing is ever sent anywhere unless this, the token below, and the upload toggle above are all filled in/enabled.",
		warning = "Filling this in (along with the token below and enabling the upload toggle above) will upload your route data - which includes your account hash, every location you visit, and (if enabled) your XP gains and hitpoints changes - to the GitHub repository you specify. Only do this if you trust the destination repo and whoever it belongs to.",
		position = 8
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
		position = 9
	)
	default String githubToken()
	{
		return "";
	}
}
