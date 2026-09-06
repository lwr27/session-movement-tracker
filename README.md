
One file per account per day, inside a folder per month. Nothing
leaves your computer unless you explicitly opt in (see below).

Local data is deleted automatically after a configurable number of
months (3 by default, set to 0 to keep everything forever). This only
ever affects files on your own computer; anything already uploaded to
GitHub is never touched by this cleanup.

## Optional: GitHub upload

Two config fields, both blank by default:

- **GitHub repo** - an `owner/repo` you control (e.g. `you/your-map-repo`)
- **GitHub token** - a Personal Access Token with write access to that
  repo's contents

If you fill in *both* (and turn the upload toggle on), the plugin will
push your local file to
`docs/route-data/<account-hash>-<year>-<month>-<day>.json` in that repo
on a separate, longer timer than local saves (5 minutes by default).
Local saves stay frequent for crash safety; uploads are deliberately
less frequent since every upload creates a commit, and the data is only
ever viewed back retrospectively, never watched live. Leave either
field blank and everything stays fully local; this is entirely
optional and off by default.

**Be aware:** if enabled, this uploads your account hash and every
location you visit to the repository you specify. Only point it at a
repo you control and trust.

## Configuration

| Setting | Default | Description |
|---|---|---|
| Teleport detection threshold | 4 tiles | Minimum distance between two ticks to count as a teleport rather than walking/running |
| Local save interval | 60 seconds | How often the current session is written to disk |
| Track XP gains | On | Records per-skill XP gained since the last save, tagged with position |
| Keep local data for (months) | 3 | Local route data older than this is deleted automatically. Set to 0 to keep forever |
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
      { "ty": "xp", "t": [2653, 2654, 0], "s": 1784894410, "xp": { "Woodcutting": 875 } }
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
any), `xp` (per-skill XP gained since the previous xp event, `xp`
events only).

## Privacy

This plugin only ever records your own character's tile position,
movement, bank-open events, and (if enabled) your own XP gains, all
timestamped. It does not read chat, inventory contents, other players,
or anything beyond location, run state, and the event types above.
