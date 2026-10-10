# PlanRecorder

[README en français](README.md)

Fork of [AudioRecorder](https://github.com/Dimowner/AudioRecorder) (v2.5) for **calendar-aware lecture notes** (DAVx⁵ / Android calendar).

| | |
|---|---|
| Application ID | `com.planrecorder` (debug: `com.planrecorder.debug`) |
| License | Apache 2.0 (based on AudioRecorder) |
| Icon | [Lucide mic-signal](https://lucide.dev/icons/mic-signal) (ISC) — thanks Lucide |

## Features

1. **Active calendar event** lookup via `CalendarContract` when recording starts
2. **Source calendar** filter (or all calendars)
3. Configurable folder / file **patterns** with tappable tags (`[id]`, `[ue]`, date/time…)
4. **SAF export** to a one-time chosen root folder
5. **Opus** + **Filter for voices** preset
6. **Custom identifiers** (`UE:1+`, `MATH:3`…) + remembered ID list
7. Optional **live AGC** and **peak normalize → WAV** on export
8. **Current-class widget** and **reminder X minutes** before the slot
9. **Markers** while recording → Obsidian-style Markdown  
   `[[file.opus#t=508|08:28]] start of lecture`

## Smart folder matching

If the pattern yields `UE581 seminar croquette` but `UE581 seminar croquette and tech` already exists under the SAF root, PlanRecorder **reuses the existing folder** (and the reverse), as long as the identifier matches.

## No event / ambiguity

- suggestions from existing ID folders + remembered IDs
- prefill is editable in the end-of-recording dialog

## Pattern tags

| Tag | Meaning |
|-----|---------|
| `[calendar]` | Calendar display name |
| `[title]` | Full event title |
| `[title_rest]` | Title without identifier prefix |
| `[id]` / `[ue]` | Extracted id (`UE323`, …) |
| `[NN]` / `[nn]` | Session number (01… / 1…) |
| `[dd]` `[mm]` `[yy]` `[yyyy]` | Date |
| `[HH]` `[MM]` `[SS]` | Time (`[MM]` = minutes, `[mm]` = month) |

Defaults: folder `[calendar]/[id] [title_rest]` · file `[NN]-T[HH][MM][SS]-[dd][mm][yy]-[id]`

## Build / install

```bash
./gradlew :app:assembleDebugConfigDebug
adb install -r app/build/outputs/apk/debugConfig/debug/app-debugConfig-debug.apk
```

Local release (artifacts under `release/`, gitignored):

```powershell
.\scripts\release.ps1
# or with a GitHub release:
.\scripts\release.ps1 -Version 1.0.0 -CreateGithubRelease
```

## First-time setup

1. **Settings → PlanRecorder**
2. Pick the **export folder** (SAF)
3. Pick the **calendar** (e.g. DAVx⁵)
4. Adjust patterns / identifier rules
5. Optional: voice filter, AGC, normalize, reminder
6. Grant **Calendar** (+ notifications for reminders)
7. Record during an event, confirm the save dialog

## Publish to a new GitHub repository

```bash
gh repo create PlanRecorder --public --source=. --remote=origin --description "Calendar-aware audio notes for lectures (fork of AudioRecorder)"
git push -u origin HEAD
```

Keep upstream for merges:

```bash
git remote add upstream https://github.com/Dimowner/AudioRecorder.git   # if needed
git fetch upstream
git merge upstream/master
```

Java packages stay `com.dimowner.audiorecorder…` to ease merges; only `applicationId` / app label are PlanRecorder.

## Credits

- [AudioRecorder](https://github.com/Dimowner/AudioRecorder) — Dmytro Ponomarenko (Apache 2.0)
- Icon [mic-signal](https://lucide.dev/icons/mic-signal) — [Lucide Icons](https://lucide.dev) (ISC)
