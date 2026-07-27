# Session Movement Tracker

A RuneLite plugin that records a session-by-session timeline of your
character's movement, including walking, running, teleports, and bank
visits, all saved to a local file on your own computer.

It was built to power a companion web map (a personal OSRS XP/activity
tracker) that replays your sessions as an animated route on an
interactive map, but the recorded data is plain, readable JSON and
useful on its own for anyone who wants a private log of where they went
and when.

## What it tracks

For each play session (login to logout), the plugin records a
chronological list of events:

- **Walks** - every continuous stretch of ground movement, stored as
  a start tile, an end tile, and (only where you actually turned a
  corner) a list of waypoints tracing the real shape of the path. A
  straight run costs nothing extra to record; a winding route costs
  one point per turn, not one per tile.
- **Teleports** - any jump further than a normal step (the distance
  threshold is configurable), or any move between planes/floors. Where
  possible, the destination is matched against a small built-in table
  of known teleport locations and labelled (e.g. "Lumbridge Home
  Teleport").
- **Bank visits** - the moment a bank interface is opened.

Sessions that are interrupted by a crash or force-close and resumed
within a few minutes are merged back into the same session rather than
split into two, so a brief disconnect doesn't fragment your log.

## Where the data goes

Everything is written locally first, to:

```
~/.runelite/route-tracker/<your-account-hash>-<year>-<month>.json
```

One file per account per month. Nothing leaves your computer unless
you explicitly opt in (see below).

## Optional: GitHub upload

Two config fields, both blank by default:

- **GitHub repo** - an `owner/repo` you control (e.g. `you/your-map-repo`)
- **GitHub token** - a Personal Access Token with write access to that
  repo's contents

If you fill in *both*, the plugin will also push your local file to
`docs/route-data/<account-hash>-<year>-<month>.json` in that repo
every time it saves. Leave either field blank and everything stays
fully local; this is entirely optional and off by default.

**Be aware:** if enabled, this uploads your account hash and every
location you visit to the repository you specify. Only point it at a
repo you control and trust.

## Configuration

| Setting | Default | Description |
|---|---|---|
| Teleport detection threshold | 4 tiles | Minimum distance between two ticks to count as a teleport rather than walking/running |
| Local save interval | 60 seconds | How often the current session is written to disk |
| GitHub repo (optional) | *blank* | Destination repo for uploads, `owner/repo` |
| GitHub token (optional) | *blank* | Personal Access Token for the repo above (masked in the config UI) |

## Data format

Each monthly file is a JSON array of sessions:

```json
[
  {
    "id": "s_1784894382",
    "start": 1784894382,
    "end": 1784894419,
    "events": [
      { "ty": "tp", "f": [2757, 3479, 0], "t": [2654, 2655, 0], "s": 1784894405, "lbl": "Pest Control" },
      { "ty": "walk", "f": [2654, 2655, 0], "t": [2653, 2654, 0], "s": 1784894407, "e": 1784894409 }
    ]
  }
]
```

Field names are kept short deliberately to keep file size down:
`ty` (type), `f`/`t` (from/to, as `[x, y, plane]`), `w` (walk
waypoints, flat `[x1, y1, x2, y2, ...]`, present only where the path
turned), `s`/`e` (start/end time, epoch seconds), `lbl` (matched
teleport label, if any).

## Privacy

This plugin only ever records your own character's tile position,
movement, and bank-open events, all timestamped. It does not read
chat, inventory contents, other players, or anything beyond location
and the three event types above.
