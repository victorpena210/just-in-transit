# Just in Transit update — October 16, 2026

This source update adds browser-separated saved commutes and reports, estimated-versus-confirmed trip comparisons, optional Web Push, a broader VIA route comparison, and an optional Jev evaluation panel. It does not deploy itself or enable a paid service automatically.

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

From a departure or active trip, choose **Try commute assistant**, optionally describe what is happening, then choose **Evaluate with Jev**. The request sends selected public stops, route/direction, current phase, timing margins, feed status, relevant official advisories and your optional message. It excludes saved home/work addresses, coordinates and browser credentials. Do not type personal information into the optional message.

The panel shows the typed judgment and model confidence, retains its public advisory evidence, and lets you record helpful/not helpful/incorrect/not sure feedback. Model confidence is not the probability of catching a bus. Evaluations and feedback expire after 30 days and can be deleted. Optional rider-update text is not retained by this app. Review TypeSafe's applicable data terms separately. Feedback collection does not train a personal model automatically.

The integration uses `POST https://api.typesafe.ai/v1/systemone`, server-side bearer authentication, bounded response parsing and a request timeout. A failure leaves the deterministic planner usable. No live API key was available during this implementation, so acceptance against your TypeSafe account is still required before relying on evaluations.

## Validation and remaining checks

The Java 17 / Spring Boot 4.1.1 production JAR packaged successfully. The Maven suite passed: 207 tests, zero failures/errors, one optional captured-live-feed test skipped. Coverage includes browser ownership, import, CSRF, route alternatives, report comparisons/export, Jev request/response validation and failure isolation, push endpoint checks, reminder cancellation, and independent decryption/signature verification of encoded Web Push.

Browser checks passed using the real Spring-rendered pages and synthetic transit data: save/reload, separate browser profiles, Jev evaluation, alternative routes, and five pages at 320/390/768/1280 px, with no JavaScript errors. Phone layouts were visually reviewed. An upgrade check on a copy of the uploaded H2 schema preserved seeded legacy settings/reports and created the new tables; this is not a hosted MySQL migration test. The cached VIA schedule parsed all 88 routes / 14,665 trips, and a representative alternatives search completed successfully.

Real-device push delivery and a paid Jev API call require your credentials and phone. Live VIA accuracy, accessible walking paths and production-scale load have not been established by these tests. The original intermittent production submission error was not reproduced from its original server log; the new reference IDs make a future incident diagnosable.

The archive is source-only. It excludes databases, cached feeds, API keys, build output and Git history.
