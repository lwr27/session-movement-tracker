# Session Movement Tracker

A RuneLite plugin that records a session-by-session timeline of your
character's movement, including walking, running, teleports, and bank
visits, all saved to a local file on your own computer. Optionally
records XP gains and hitpoints changes too, so a stationary training
session isn't left completely empty.

It was built to power a companion web map (a personal OSRS XP/activity
tracker) that replays your sessions as an animated route on an
interactive map, but the recorded data is plain, readable JSON and
useful on its own for anyone who wants a private log of where they went
and when.

## What it tracks

For each play session (login to logout), the plugin records a
chronological list of events:

- **Walks** - every continuous stretch of ground movement, stored as
  a start tile, an end tile, and a list of waypoints tracing the real
  shape of the path. A waypoint is added wherever you turned a corner
  or toggled run, whichever comes first, so a straight run with no
  toggles costs nothing extra to record while a winding or run-toggling
  route costs one point per event, not one per tile. Each recorded
  point also carries whether run was on or off at that moment.
- **Teleports** - any jump further than a normal step (the distance
  threshold is configurable), or any move between planes/floors. Where
  possible, the destination is matched against a small built-in table
  of known teleport locations and labelled (e.g. "Lumbridge Home
  Teleport").
- **Bank visits** - the moment a bank interface is opened.
- **XP gains** - optional, on by default. Every XP drop is noted the
  second it lands, and all drops since the last local save are packed
  into one event (about 10 bytes per drop), tagged with your position.
  This is the only thing recorded at all during stationary training
  (mining, fishing, AFK combat, etc.), where there's no movement to
  track otherwise. Pending XP and hitpoints changes are also written
  out just before a teleport, so they stay attributed to where they
  actually happened.
- **Hitpoints changes** - optional, on by default. Checks your current
  hitpoints every game tick and notes any change, then bundles all the
  changes since the last local save into a single event with your max
  hitpoints at the time. Only changes are stored, so a session spent at
  full health writes nothing at all; data is only added while taking
  damage, healing, or regenerating back to full. Logging in below full
  health counts as a change, so that starting value is captured too.

Inside instances (raids, boss rooms, player-owned houses and so on),
positions are recorded as the instance's template coordinates, i.e.
where that piece of map lives in the real world, rather than the
temporary coordinates the instance was allocated at. Events recorded
inside an instance are additionally flagged with `"i": 1`, since some
templates (houses in particular) are themselves off the main map.
Events recorded while aboard a boat are flagged with `"b": 1`.

Sessions that are interrupted by a crash or force-close and resumed
within a few minutes are merged back into the same session rather than
split into two, so a brief disconnect doesn't fragment your log.

## Where the data goes

Everything is written locally first, to:

```
~/.runelite/route-tracker/<year>-<month>/<your-account-hash>-<year>-<month>-<day>.json
```

One file per account per day, inside a folder per month. Nothing
leaves your computer unless you explicitly opt in (see below).

Local data is deleted automatically after a configurable number of
months (1 by default, set to 0 to keep everything forever). This only
ever affects files on your own computer; anything already uploaded to
GitHub is never touched by this cleanup.

## Optional: GitHub upload

Two config fields, both blank by default:

- **GitHub repo** - an `owner/repo` you control (e.g. `you/your-map-repo`)
- **GitHub token** - a Personal Access Token with write access to that
  repo's contents

If you fill in *both* (and turn the upload toggle on), the plugin will
push your local file to
`docs/route-data/<account-hash>/<year>-<month>-<day>.json` in that repo
(one folder per account, created automatically on first upload)
on a separate, longer timer than local saves (5 minutes by default).
Local saves stay frequent for crash safety; uploads are deliberately
less frequent since every upload creates a commit, and the data is only
ever viewed back retrospectively, never watched live. Leave either
field blank and everything stays fully local; this is entirely
optional and off by default.

If the same account is tracked from more than one computer, each
upload merges with whatever is already on GitHub for that day rather
than replacing it, so sessions recorded on another machine are kept.

**Be aware:** if enabled, this uploads your account hash and every
location you visit to the repository you specify. Only point it at a
repo you control and trust.

## Configuration

| Setting | Default | Description |
|---|---|---|
| Teleport detection threshold | 4 tiles | Minimum distance between two ticks to count as a teleport rather than walking/running |
| Local save interval | 60 seconds | How often the current session is written to disk |
| Track XP gains | On | Records per-skill XP gained since the last save, tagged with position |
| Track hitpoints | On | Records changes to current hitpoints since the last save. Nothing is written while at full health |
| Keep local data for (months) | 1 | Local route data older than this is deleted automatically. Set to 0 to keep forever |
| GitHub repo (optional) | *blank* | Destination repo for uploads, `owner/repo` |
| GitHub token (optional) | *blank* | Personal Access Token for the repo above (masked in the config UI) |
| GitHub upload interval | 300 seconds | How often data is uploaded, separate from the local save interval |

## Data format

Each daily file is a JSON array of sessions:

```json
[
  {
    "id": "s_1784894382",
    "start": 1784894382,
    "end": 1784894419,
    "events": [
      { "ty": "tp", "f": [2757, 3479, 0], "t": [2654, 2655, 0], "s": 1784894405, "lbl": "Pest Control" },
      { "ty": "walk", "f": [2654, 2655, 0], "t": [2653, 2654, 0], "rAll": 1, "s": 1784894407, "e": 1784894409 },
      { "ty": "xp", "t": [2653, 2654, 0], "s": 1784894410, "e": 1784894418, "sk": ["Woodcutting"], "d": [0, 0, 25, 4, 0, 25, 8, 0, 25] },
      { "ty": "hp", "t": [2653, 2654, 0], "mx": 99, "hp": [0, 91, 2, 84, 5, 99], "s": 1784894412, "e": 1784894417 }
    ]
  }
]
```

Field names are kept short deliberately to keep file size down:
`ty` (type), `f`/`t` (from/to, as `[x, y, plane]`), `w` (walk
waypoints, flat `[x1, y1, x2, y2, ...]`, present only where the path
actually turned or run was toggled), `r` (run state per point, only
present if it changed within the segment), `rAll` (run state for the
whole segment, used instead of `r` when it never changed), `s`/`e`
(start/end time, epoch seconds), `lbl` (matched teleport label, if
any), `sk`/`d` (XP drops, `xp` events only: `sk` is the list of skill names
used by this event and `d` is flat `[offset, skillIndex, amount, ...]`
triples, offset in seconds after `s`; the example above is three
Woodcutting drops of 25 at 0s, 4s and 8s. Files written by version 1.1
carry an `xp` map of per-skill totals instead), `hp` (hitpoints changes as flat `[offset, value, ...]`
pairs, where each offset is seconds after `s`, `hp` events only), `mx`
(max hitpoints at the time, `hp` events only). In the example above,
the player was on 91 hitpoints at `s`, dropped to 84 two seconds later,
and ate back to 99 at five seconds. A value above `mx` is possible
after a Saradomin brew or similar overheal. `i` (present and set to 1
on any event recorded inside an instance, see above), `b` (present and
set to 1 on any event recorded aboard a boat).

## Privacy

This plugin only ever records your own character's tile position,
movement, bank-open events, and (if enabled) your own XP gains and
hitpoints, all timestamped. It does not read chat, inventory contents,
other players, or anything beyond location, run state, and the event
types above.
