# Just in Transit update — October 16, 2026

This source update adds browser-separated saved commutes and reports, estimated-versus-confirmed trip comparisons, optional Web Push, a broader VIA route comparison, and an optional Jev evaluation panel. It does not deploy itself or enable a paid service automatically.

## Arrival windows and latest departures

Timing and waiting preferences appear first in setup and in the saved daily form. Set the destination deadline, arrival cushion, maximum minutes early, and minimum/maximum waits at the first stop and transfer. Walking remains separate. For example, a 4:00 PM deadline, 5-minute arrival cushion and 15-minute maximum early arrival gives an estimated arrival window of 3:45–3:55 PM when no extra traffic allowance is set. A 5-minute extra delay allowance narrows the estimate window to 3:45–3:50 PM so the second bus can use that allowance and still retain the arrival cushion. Contradictory limits are rejected.

The default recommendation is the latest departure among candidates meeting every limit. Shorter-trip and more-room preferences remain available under advanced settings, but both respect the arrival window before ranking. If none fit, the planner keeps the configured limits and explicitly labels the closest on-time compromises; when there is no on-time connection, it shows the earliest late arrivals. The early-arrival cap is ignored in leave-after mode. Estimates and maximum waiting preferences are planning constraints, not guarantees of actual service.

Old saved settings gain a 15-minute cap, or the existing arrival cushion plus delay allowance if that is larger, and switch to latest-departure ranking. Saved stops, walking times and other settings are retained. Once saved with the new cap, an explicitly selected ranking is preserved. Daily edits apply only to the current plan; edit and save the setup to change usual preferences. Recovery and live refresh retain the arrival window and show when a selected trip moves outside it.

Direct/one-transfer alternatives between the selected stops also appear in the results, searched up to six hours before the arrival deadline (or six hours after a leave-after time). They use published transfer links and respect the walking/traffic allowance; fallback cards say which preferences they miss. This is not an exhaustive journey search involving arbitrary nearby stops or multiple transfers. Other routes remain comparison-only; step tracking and phone reminders still use the saved 24/93 corridor. No alternative-route search is offered after starting at a stop or joining a bus.

When Jev is configured, **Ask Jev to compare these departures** refreshes the shown corridor options and checks the other-route choices before sending up to eight options that meet all limits. Too-early, canceled, unreachable and otherwise out-of-window choices are excluded. The typed result must select one of those IDs or abstain; unknown IDs are rejected. Public option summaries and matched advisories are saved with the evaluation, never home/work addresses or map points. Jev does not automatically select or start a trip. The standard planner works with Jev disabled or unavailable. A real provider call still requires the deployment credentials below.

## Install over your existing project

1. Stop the running app. Keep your existing project and database.
2. Extract this archive into a different folder.
3. From the extracted `just-in-transit` folder, run:

   ```sh
   bash apply-update.sh /absolute/path/to/your/original/just-in-transit
   ```

   The script first creates a timestamped backup beside your original project. It copies the updated source, configuration and scripts, preserving the original `data/`, Git history and local environment files. Check any local customizations before applying. If OpenSSL is available, it generates a private import code in `data/owner-restore.properties` without displaying it.
4. In your original project, run `sh mvnw clean test`, then `sh mvnw package`. Java 17 or newer is required. Restart with your existing deployment profile. Locally, `sh mvnw spring-boot:run` uses `http://localhost:8081`.
5. To bring old saved settings and reports into your current browser, open `/restore` and enter the value of `jit.legacy-import-code` from the generated file. If it was not generated, set `JIT_LEGACY_IMPORT_CODE` to a new random secret of at least 24 characters before starting. Do not share or commit this code. Each old direction/report can be claimed only once; existing settings in the destination browser are not overwritten. Remove the code after restoring what you need.

For an existing Railway deployment, deploy the source through your usual Git workflow and back up the hosted database first. Set the restore secret as `JIT_LEGACY_IMPORT_CODE` there; a local file is not uploaded by this source archive. Keep your existing MySQL variables and `public,mysql` profiles. The explicit public profile now starts without requiring an owner password. It requires HTTPS and `JIT_FORWARD_HEADERS=framework` behind Railway's trusted proxy. Do not expose a direct untrusted route to the app when trusting forwarding headers.

The current development schema update setting adds the new tables/columns. Existing saved settings are retained as legacy records. A fresh browser starts with its own settings until you import. Browser credentials are random HttpOnly cookies: anyone sharing that browser profile shares its data; clearing its cookies loses access. This is not account login, cross-device sync, or account recovery. Keep the original backup for rollback; stop the new app and restore both source and database from that backup if necessary.

The GitHub integration also preserves the newer complete-trip ranking preferences, configurable traffic allowances and active-trip guidance already on `main`.

## What changed

| Area | Behavior |
| --- | --- |
| Public access | Correctly honors explicit public mode; settings, reports, push subscriptions and Jev evaluations are scoped to one browser credential. |
| Reliability | Optional completed-trip reports preserve the initial departure, boarding, transfer and arrival estimates alongside your button-confirmed times and original estimate source. Add a note or download CSV from Trip history. Changing buses is flagged. These are rider confirmations, not independently measured vehicle accuracy. |
| Other routes | Searches all loaded VIA routes for direct or one-transfer journeys within six hours. It checks calendars, stop order, pickup/drop-off rules and live cancellations. Transfers use the same stop or a GTFS-listed pair, with your walking time or VIA's larger minimum. |
| Notifications | Server checks opted-in trip watches about every 30 seconds. It can send departure reminders, transfer warnings, an upcoming second-bus reminder, unavailable-feed notices and material arrival-estimate changes. Messages identify estimates; they are not guarantees. |
| Jev | Evaluates the relevance/attention level of up to three current advisories and classifies an optional rider update into a proposed interface action. It cannot change routing, hide advisories or confirm progress. |
| Errors | Unexpected application errors carry a reference you can match to server logs. Invalid forms retain usable validation behavior. |

Other routes is a comparison view: it does not yet launch step-by-step tracking for arbitrary routes, find two-transfer journeys, calculate arbitrary transfer walks, or provide an independent traffic forecast. Check crossings and access restrictions yourself.

## Enable Web Push

Keep the app running on an HTTPS origin. Generate one persistent VAPID key pair locally:

```sh
java scripts/GeneratePushKeys.java
```

Set the two printed values as deployment secrets `JIT_PUSH_PUBLIC_KEY` and `JIT_PUSH_PRIVATE_KEY`, and set `JIT_PUSH_SUBJECT=mailto:YOUR_REAL_CONTACT_EMAIL`. Keep the private key secret and retain the same pair across restarts. Rotating it requires browsers to resubscribe. A `.env` file alone is not automatically loaded by Spring; configure environment variables in your host or run configuration.

Restart, open the app on your phone, and use **Enable notifications**. On iPhone/iPad, add the app to the Home Screen, open that installed app, then grant permission (iOS/iPadOS 16.4 or later). Before starting, choose **Remind me** for a departure today. Starting or resuming a trip in a subscribed browser watches its progress. **Stop this trip’s reminders**, ending/finishing/discarding a trip, editing the plan, or signing out cancels its watch. **Turn notifications off** removes the subscription. A push already accepted by the delivery provider may still arrive after cancellation.

This version supports Chrome/FCM, Firefox/Mozilla and Safari/Apple push endpoints. Delivery depends on permission, the device, provider and server uptime; no precise delivery time is promised. Run one application instance for this first scheduler version; multi-instance delivery coordination is not implemented. The current worker rotates through at most 100 watches per batch, so more active browsers increase notification latency. Expired watches are removed and invalid subscriptions are discarded. Push is completely disabled until signing credentials are configured.

## Enable Jev by TypeSafe AI

Configure these deployment variables, then restart:

```text
TYPESAFE_API_KEY=<your TypeSafe AI API key>
JIT_JEV_MODE=shadow
JIT_JEV_MODEL=jev-1.13.0
JIT_JEV_HOURLY_BUDGET=60
```

`off` is the default; only `off` and `shadow` are supported. The model version is configurable. The global process budget defaults to 60 evaluations/hour, with a 60-second browser-session cooldown and a short circuit breaker after API failures. A restart resets the in-memory budget. Treat it as a request guard, not a billing cap. API usage can incur charges under your TypeSafe plan.

From a departure or active trip, choose **Review this trip with Jev** or **Review advisories with Jev**, optionally describe what is happening, then choose **Evaluate with Jev**. The request sends selected public stops, route/direction, current phase, timing margins, feed status, relevant official advisories and your optional message. It excludes saved home/work addresses, coordinates and browser credentials. Do not type personal information into the optional message.

The panel shows the typed judgment and model confidence, retains its public advisory evidence, and lets you record helpful/not helpful/incorrect/not sure feedback. Model confidence is not the probability of catching a bus. Evaluations and feedback expire after 30 days and can be deleted. Optional rider-update text is not retained by this app. Review TypeSafe's applicable data terms separately. Feedback collection does not train a personal model automatically.

The integration uses `POST https://api.typesafe.ai/v1/systemone`, server-side bearer authentication, bounded response parsing and a request timeout. A failure leaves the deterministic planner usable. No live API key was available during this implementation, so acceptance against your TypeSafe account is still required before relying on evaluations.

## Validation and remaining checks

The Java 17 / Spring Boot 4.1.1 production JAR packaged successfully. The Maven suite passed: 221 tests, zero failures/errors, one optional captured-live-feed test skipped. Coverage includes maximum early-arrival limits, latest-departure ranking, daily preference persistence, validated Jev comparisons, browser ownership, import, CSRF, route alternatives, report comparisons/export, Jev request/response validation and failure isolation, push endpoint checks, reminder cancellation, and independent decryption/signature verification of encoded Web Push.

Browser checks passed using the real Spring-rendered pages and synthetic transit data: save/reload, daily wait/arrival edits, explicit early fallback, latest fitting departure, validation recovery, Jev comparison, alternative routes, and five pages at 320/390/768/1280 px, with no JavaScript errors. Phone layouts were visually reviewed. An upgrade check on a copy of the uploaded H2 schema preserved seeded legacy settings/reports and created the new tables; this is not a hosted MySQL migration test. The cached VIA schedule parsed all 88 routes / 14,665 trips, and a representative alternatives search completed successfully.

Real-device push delivery and a paid Jev API call require your credentials and phone. Live VIA accuracy, accessible walking paths and production-scale load have not been established by these tests. The original intermittent production submission error was not reproduced from its original server log; the new reference IDs make a future incident diagnosable.

The archive is source-only. It excludes databases, cached feeds, API keys, build output and Git history.
