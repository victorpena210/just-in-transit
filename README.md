# Just in Transit — my commute

One personal trip: **24 from E. Houston & Mittman → Martin & Leona; 93 from Martin opposite Leona → Fiesta Texas employee entrance.**

## How it chooses a departure

1. Match the 24 arrival and 93 departure first. Allow a **3-minute walk between the stops, followed by 3–10 minutes waiting** before boarding the 93. The 10-minute maximum applies to standing wait, so the whole transfer may take 6–13 minutes. These walking and cushion estimates are assumptions, not guaranteed bus reliability.
2. Keep only connections that arrive by your selected Six Flags time. If you add a final walk from the employee entrance bus stop, that walk must fit before the deadline too. The default is arrival at the bus stop, with zero extra walking minutes.
3. Subtract **5 minutes walking from home + 3 minutes waiting at Mittman & Houston** from the 24 departure.
4. Choose the **latest feasible departure from home**. A slightly shorter transfer must not make you leave much earlier when a later qualifying trip exists.

No matching trip means **No matching connection**. The app does not silently increase the wait limit, remove the boarding cushion, shorten the walk, or recommend a late arrival. Buses still follow VIA's schedule; the app cannot make two services line up when they do not.

The fixed GTFS stops are 81799 → 76779 on the 24, then 76773 → 83463 on the 93. Stop order and service calendars establish direction. Existing live-feed handling rejects canceled trips/skipped stops and labels schedule fallbacks. Comparisons use seconds, so a wait of 10 minutes 1 second after the transfer walk is rejected.

## Using the app

Enter the travel date and **Be at Six Flags by**, then choose **Find my departure**. The large time is when to leave home. The timeline gives the first stop, both buses, transfer, and arrival. A collapsed option adds walking time after the employee entrance bus stop if needed.

Date, arrival time and final-walk minutes are saved in the existing database, in a separate personal-settings table keyed to the existing browser credential. Reopening in the same browser restores your settings; another browser does not read or overwrite them. A future travel date stays selected; an old date moves forward to today. The route, home walk and transfer rules are fixed. Existing saved commutes are not imported into this plan, so old directions or waiting preferences cannot alter it.

The page checks again every 30 seconds while open and when you return to it. Changing an input pauses checks until you submit it. It is a leave-home planner, not a journey tracker: after the departure time passes, the next check may find a different connection or none. The page has no background notifications or GPS tracking. If a refresh fails, old times are marked as needing another check. No authenticated plan is cached offline.

## Run and update

Java 17 and the existing Maven wrapper are required. Keep your existing deployment/profile/database environment settings.

```bash
sh mvnw clean test
sh mvnw spring-boot:run
```

Local address: http://127.0.0.1:8081. For an existing hosted app, use its existing protected-access configuration; this update retains it.

To update an existing checkout from this ZIP, stop the app, extract the ZIP, and run this **from the extracted just-in-transit folder**:

```bash
bash apply-update.sh "$HOME/Desktop/IdeaProjects/Spring-Projects/just-in-transit"
cd "$HOME/Desktop/IdeaProjects/Spring-Projects/just-in-transit"
sh mvnw clean test
```

The updater backs up the original source and local database, then copies the update. It preserves Git history, environment files and existing database contents. Restart/redeploy after the tests pass. This ZIP contains source only; it does not contain your database, Git history, credentials or a bundled transit schedule. Existing installations retain their cached VIA feed; clean installations download it normally.

## Verification

Run just the 33 planner checks with the project dependency classpath:

```bash
bash scripts/test_personal_planner.sh
```

This covers 33 checks, including exact transfer limits, optional final walking time, latest departure ranking, missed departures, cancellations, skipped stops, stale data, reversed routes, service dates and trips crossing midnight. `PersonalCommuteWebTests` adds Spring rendering, validation, persistence, preview and CSRF coverage.

The 33 timing checks passed during development and JavaScript syntax was checked. The full Spring/Maven suite could not run in the editing environment because Maven Central was unreachable; run `sh mvnw clean test` before deploying.

## Compatibility

`jit.personal.enabled=true` is the default. It disables the old route setup, maps, alternatives, Jev, trip tracking, legacy import, notifications and history controllers; those screens are no longer part of the app's normal flow. Background watches from the former workflow are paused in personal mode. The old code and database tables remain for compatibility with existing installs. Setting `jit.personal.enabled=false` temporarily restores the previous workflow. Its historical documentation is in `docs/legacy-workflow.md`. Existing workflow tests explicitly select that mode; the personal web tests and default context test exercise the simplified mode.
