# Just in Transit

A San Antonio commute assistant with guided 24 → 93 / 93 → 24 trips and direct/one-transfer route comparisons alongside your departure results.

## Current update — October 16, 2026

### Bus confirmation and reliable trip saving

- **Find my bus:** choose a vehicle on the map or enter its vehicle number, then confirm “I'm on this bus.” Either leg can be the starting point. Earlier steps remain unrecorded. Both route directions remain selectable, including a downtown loop; the driver-confirmed continuation flag is a rider note, not an arrival prediction.
- Location suggestions use fresh bus reports, phone accuracy and repeated nearby movement. The rider confirms every match; location never advances a trip. A confirmed physical vehicle stays selected through trip-ID changes. Arrival is withheld until its fresh service instance has an upcoming, usable destination event.
- Progress is committed atomically with its opt-in report and retry receipt. Reopening with the same browser credential restores the current trip automatically, even without the old session/recovery cookie. Stale tabs cannot overwrite newer progress. Active trips still expire after 18 hours; existing checkpoints remain readable.
- While the page is open, updates persist in a browser-scoped local outbox before transmission. The page distinguishes **Waiting to sync**, **Saved**, and **Update needs review**. Reconnecting retries in order; repeated event IDs cannot advance twice. After an offline reload, the public offline page shows the last status; reconnect to resume editing/sync. No background GPS or cross-device sync is added.
- History preference is remembered in this browser. Opted-in reports update during a trip and retain unfinished trips; boarding confirmations do not invent earlier departure or boarding times. Destination, selected vehicle and history can be corrected during a trip. **Save timing as my defaults** explicitly persists daily timing preferences.

Deployment: rebuild and restart the existing application. The current schema update adds an optimistic-lock version to `journey_checkpoint`, a nullable report status and the `journey_receipt` table. Keep the existing database; use the configured persistent MySQL database or a persistent volume for file-backed H2. Browser outboxes cannot recover deleted server databases or cleared browser storage. No hosting/database credentials or infrastructure settings are changed by this update.

Validation: 229 Maven tests (228 passed, one optional captured-feed test skipped). A DOM integration check against a running Spring app verified lost-acknowledgement recovery, reload, duplicate retry, two ordered offline confirmations, reconnect, report saving and ending the trip. Browser rendering/mobile visual checks were blocked by the available browser environment; hosted deployment and MySQL migration were not verified.


Read [UPDATE-GUIDE.md](UPDATE-GUIDE.md) for installation, restoration of existing data, deployment variables and limitations.

- Arrival deadlines and first-stop/transfer wait windows now lead setup and the saved daily form. Daily changes remain temporary unless **Save timing as my defaults** is chosen.
- Added a maximum early-arrival limit (15 minutes by default, increased only when needed to accommodate an existing arrival cushion plus delay allowance). Existing saves without this field adopt latest-departure ranking; stops and walking times are retained.
- Latest departure is the default among trips meeting the arrival window, wait limits and traffic allowance. Too-early fallbacks are labeled with the exact shortfall instead of appearing as recommendations that meet preferences.
- Other direct/one-transfer routes between the selected stops appear with the same arrival deadline and limits. Alternative-route comparisons do not start corridor tracking.
- Optional Jev comparison accepts only refreshed, validated departure IDs or an uncertain response. It cannot override your limits or change a trip; unavailable Jev is clearly labeled.
- Fixed explicit public-profile startup and isolated saved commutes/history by browser, with a protected import path for older data.
- Added initial estimates versus rider-confirmed times, notes and CSV export.
- Added opt-in Web Push and an installable app manifest; configure signing credentials before enabling notifications.
- Added direct/one-transfer alternatives across the complete VIA schedule.
- Added optional Jev shadow evaluation of advisories and rider updates, with feedback and safe failure behavior. It is off by default and cannot change routes or progress.
- Added request references for unexpected application errors.
- Merged with the latest GitHub improvements, preserving balanced/latest/safer ranking, traffic allowances, complete-trip breakdowns and active-trip guidance.

The current Maven suite passes **221 tests: 220 passed, one optional captured-feed test skipped** on Java 17 / Spring Boot 4.1.1. Jev account acceptance and real-phone push delivery still need your configuration. See the current guide for the exact checks and remaining limits.

## Historical release notes

Everything below records earlier releases. Statements below about shared settings, unavailable Maven dependencies, no new dependencies, or lack of notifications are historical and are superseded by the current guide.

## Reliability and waiting preferences — October 15

- **Longer-lived departure plans:** preview tokens stay valid for up to 18 hours in the same browser session instead of failing at 20 minutes. Refresh checks the same selected buses against current schedule/live data. Starting always revalidates boarding and transfer feasibility. A replaced, expired or lost-session plan now offers **Find fresh departures**. This does not promise that old departure times remain usable.
- **Waiting windows:** save a minimum and maximum wait at the first stop and after the transfer walk. Existing minimum buffers are retained. New maxima default to 15 minutes at the first stop and 20 at the transfer (never below a saved minimum). Walking time is separate. At-home departure timing uses the minimum first-stop wait; at-stop planning evaluates actual remaining wait. On-board planning skips first-stop waiting. When no on-time trip meets every preference, the app shows a clearly labeled fallback rather than hiding available service.
- **Ranking:** arrive-by plans from the starting point prefer all requested buffers/wait limits, then less total waiting, then later departure. Departure and stage-start modes prefer matching preferences among on-time candidates and then earliest arrival. When every option is late, earliest arrival wins. The existing 90-minute transfer search cap remains.
- **Boarding times:** the main recommendation now shows first boarding, Martin & Leona arrival, second boarding, and waiting after the transfer walk. These times refresh with the recommendation. Stale displayed times are explicitly labeled.
- **Save and error recovery:** successful saves redirect to a GET page, preserving the submitted date and settings. Reloading does not repeat the save. Invalid wait windows return inline validation. A reproduced record-binding/template crash on malformed number/date/time input was repaired so the entered form and its field errors remain renderable. Database errors return a readable recovery page; the valid submitted form is remembered in the session before attempting a save. General errors also have a branded recovery page. The user's original intermittent production submit exception was not reproduced from its original server log, so these changes must not be described as proof of that exact root cause.
- **Isolated tests:** Surefire and the context-startup test use an in-memory H2 database and disable external feeds, avoiding the personal file database lock seen in the uploaded test report.
- **Traffic limits:** the interface explicitly explains that traffic/construction affects estimates only through VIA's reported predictions/advisories. No independent traffic forecast, automatic rerouting or background notifications were added.

### Apply this update

Stop the running app first. Run `bash apply-update.sh /absolute/path/to/your/original/just-in-transit` from this extracted project. The script backs up the existing source and local data, then copies source/configuration while preserving Git history and saved commute data. Run `sh mvnw clean test` in your original project, then restart it. The development `ddl-auto=update` setting adds two nullable wait-limit columns; old records receive defaults when read.

### Validation of this build

The modified production Java classes compiled with locally available dependencies. **37 focused Java test methods passed** using a local assertion runner (not the Maven/JUnit engine). Browser checks exercised the real Spring MVC/Thymeleaf pages with synthetic transit feeds and in-memory repository doubles: save/redirect/reload, persistence of wait settings within the fixture, one-hour refresh, replaced-token recovery, invalid wait windows, malformed number/date/time input, edited daily time retention, simulated database error recovery, visible boarding times and widths of 320/390/768/1280 pixels, with no JavaScript errors. The local web harness used the available Spring Boot 4.1.0 dependencies; the project still declares 4.1.1.

The exact Maven suite and a real database migration could not run here because Maven Central dependencies were unavailable. Run the included suite on your computer before relying on this update. Live VIA accuracy and real-phone background behavior were not verified. Historical validation notes below describe earlier deliveries, not a new test run.

## Complete commute windows — October 14, 2026

- **Separate walks and waits:** setup distinguishes the walk from home to the first stop, first-stop waiting, the transfer walk, waiting after that walk, and the final walk. Each recommendation displays boarding times, waits, and door-to-door duration. Already-at-stop plans measure the wait from now; they no longer automatically claim the first boarding buffer is met.
- **Saved minimum/maximum wait windows:** the existing first-stop and transfer buffers are minimums. Preferred maximum waits are configurable (defaults for existing setups: at least 15 and 30 minutes respectively). Contradictory windows are rejected without losing form input. If no on-time option satisfies every preference, the planner explains the shortfall instead of labeling it compliant.
- **Complete-trip ranking:** inspect all feasible first/second-bus pairs rather than discarding alternatives before scoring. On-time arrival comes first, then all requested windows and allowances, then excess waiting. Balanced favors shorter complete trips; Latest favors later departures; More breathing room favors the smaller of transfer/arrival reserves. Leave-after plans prefer compliant options then earliest arrival. Late fallback still chooses the earliest actual arrival, including walking.
- **Traffic/construction allowance:** configure additional delay minutes per bus beyond the currently available estimate. A first-bus delay consumes transfer slack; a second-bus delay consumes destination slack. Bus timestamps remain the actual feed/schedule estimates, with the allowance scenario labeled separately. The first leg is not added twice to final arrival because a successful transfer boards a separately timed second bus. New work setups suggest 5 minutes; existing saves and recovery checkpoints retain 0 until edited. This is not a highway congestion feed, and no road-closure rerouting is claimed. VIA predictions and service alerts remain the live inputs. See the [GTFS realtime reference](https://gtfs.org/documentation/realtime/reference/).
- **Active trip guidance:** a prominent status and next-step panel explains what to do, compares arrival with the original plan, and shows an alternative transfer with destination ETA and lateness. A user can choose it while riding the first bus, walking between stops, or waiting. The switch rechecks availability, retains the original deadline and confirmations, and supports Undo. No automatic bus switch or invented boarding confirmation. Stale/missing predictions and offline refresh failures never receive an unqualified “On track” label.
- **Save reliability:** a successful save commits and redirects to GET, preserves the submitted travel date/stage for that result, and cannot be repeated by refreshing the result page. Persistence failures keep entries and show a retry message. Unexpected page errors use a recovery page rather than the Whitelabel screen. The reported production exception was not reproducible with the available source and fixtures; production logs are still needed to identify its exact original cause.

No account is required. Public mode still has the previously accepted shared saved settings/history; this update does not introduce private multi-user profiles or change that access model. Preference columns are nullable for existing H2/MySQL rows and receive defaults on read under the existing `ddl-auto=update` migration behavior. Active journeys remain scoped to their browser/session and recovery cookie. Existing saved work/home commutes, offline cards, and opt-in reports are retained. Updates run while the page is open; there are no background phone notifications.

## Journey update — October 14

All five requested improvements are included:

1. **Start from your actual stage.** Choose **At home / starting point**, **At the first bus stop**, or **Already on the first bus** in the daily form or setup. At-stop and on-board modes work after the leave-home estimate has passed and omit the initial walk. On-board mode requires you to choose your actual bus using the boarding/transfer estimates and trip ID; it never guesses that you boarded. Use **Refresh bus list** if needed. These two modes apply to today and plan from now; an arrival deadline stays in place, while a leave-after time is overridden by the current stage. Joining partway through does not invent earlier confirmed times.
2. **Undo last step.** Undo one progress confirmation or missed-bus replacement. It restores the previous buses, missed-bus exclusions and confirmation times. The undo snapshot survives journey recovery. Each action gets a new revision so a duplicate or stale submission cannot repeat it. Starting and finishing a trip are not undoable.
3. **Optional offline trip card.** Check **Keep an offline card on this device** while connected. After you start or resume a trip, the card saves public stop names, instructions and the last checked estimates in this browser. Reopening while offline shows a read-only card with its saved timestamp; it has no running countdown, map tiles or queued progress actions. HTTPS or localhost and a browser with service workers are required. It expires 18 hours after the original start and can be deleted from the card. Ending, finishing, discarding, deleting the commute or signing out clears the browser copy. Anyone using that browser can read a saved card. Addresses, map coordinates, authentication data and location trails are not included. Only the public HTML/JS/CSS shell is cached by the service worker; authenticated pages and API replies are never cached there.
4. **Optional trip reports.** Before starting, check **Save a trip report when I finish** (off by default). After confirming arrival, **Finish commute** records planned versus confirmed departure/arrival and whether you reported missing the selected transfer. Unknown times remain **Not recorded**. The original plan is retained when a later bus is selected. Reports use your button confirmations, not automatic tracking or measured prediction accuracy. Open **Trip history** to review or delete one/all reports. Ending an unfinished trip creates no report. Reports contain no addresses or GPS history.
5. **Separate Going Home setup.** Work and home have independent saved stops, points, walks, buffers, planning modes and usual times. Going Home defaults to **Leave after**, ranks earliest arrivals and makes no arrival-deadline claim. **Arrive by** is available for either direction. Daily overrides do not overwrite the saved usual settings.

### Check the return walk before saving

The inspected VIA schedule (service range August 24, 2026–January 10, 2027) does not put the employee-entrance stops on the downtown-bound 93. The return default is:

| Step | Route / stops |
| --- | --- |
| First bus | 93: `75229` LA CANTERA & FIESTA TEXAS DR → `76779` MARTIN & LEONA |
| Transfer walk | `76779` → `76773` MARTIN OPP LEONA |
| Second bus | 24: `76773` → `81793` E. HOUSTON & MITTMAN (opposite side from the outbound stop) |

Both endpoint walking fields start empty in a new Going Home setup: enter checked walking times or confirm map points and calculate the walks. Choose another eligible stop if it suits the actual entrance better. The planner, stop search, live vehicles, countdowns and advisories all use the selected direction and service date. The app checks stop order and pickup/drop-off permissions; reversing the route numbers alone is not treated as valid service. Walking access and gate availability still require a real check.

### Existing settings and recovery

The existing Work record remains compatible and keeps its identity; Home uses a separate record. This update adds nullable settings columns and a trip-report table. The development H2 configuration updates the schema at startup. **Stop the app and back up `data/` before updating.** Keep the original database, and do not run two copies against it at once. No dependencies or Java version were changed.

Active journeys still use a random HttpOnly, SameSite recovery cookie and an expiring database checkpoint. Reopen in the same browser after a restart to resume within 18 hours of the original start. Refresh only updates unfinished estimates and does not change the selected buses. Finishing/ending/discarding deletes the checkpoint; opted-in completed reports are separate and remain until deleted. Offline cards are per browser, not cross-device synchronization.

The recommendation refreshes while visible. Stale estimates, failed requests and edited settings become unconfirmed/update-plan states. Starting rechecks the selected service and transfer feasibility on the server. If a leave-time estimate has passed, use **Change time or current stage** to select where you actually are.

### Validation for this delivery

- Earlier delivery claimed a clean 149-test suite. The uploaded reports actually contain **147 passed, one database-lock error, and one optional captured-feed integration test skipped**; those are historical reports, not validation of this build.
- New coverage includes stage starts after leave time, explicit aboard-trip selection, undo and checkpoint recovery, correct reverse stop order, departure-only planning, separate settings, report opt-in/completion/deletion, CSRF/authentication and private offline snapshots.
- Browser checks used real Spring-rendered templates with mocked transit responses. They covered stage-aware countdowns/start buttons, required return walks, saved settings, history defaults, real service-worker installation and offline navigation, cache contents, card expiry/deletion, end/sign-out clearing and widths of 320/390/768/1280 px. No JavaScript errors were observed. Phone layouts were visually reviewed.
- These checks do not establish real VIA prediction accuracy, walking access, or real-phone HTTPS/GPS behavior. No hosting, certificates, background push alerts or automatic walking-time learning are included.

## Start in Spring Tool Suite / Eclipse

1. Unzip this project into a new folder. Keep your original project as a backup.
2. Choose **File → Import → Maven → Existing Maven Projects**.
3. Select the extracted `just-in-transit` folder containing `pom.xml`, then click **Finish**. If Eclipse already has a project with this name, remove that project from the workspace without deleting its files, then import this one.
4. Set the project JRE to **Java 17 or newer**. Let Maven download dependencies; use **Maven → Update Project** if needed.
5. Right-click `JustInTransitApplication.java` → **Run As → Spring Boot App**.
6. Open **http://localhost:8081**.
7. Follow **Starting point → Destination → Arrival time**. For each location, use **Search address**, **Set on map**, or a manually chosen stop and walking minutes. **Use my location** is available for the start. Confirm an exact result or map point, choose a nearby stop, and check the walking estimate. Click **Find my departure**.
8. If an address cannot be found, choose **Set entrance on map** or **Use stop and walking minutes** for that end of the commute. You can mix a pinned home with a manual destination. Save these settings to reuse them.

The first page load downloads VIA's schedule, which can take a little time. The default database is embedded H2; no MySQL installation or account is needed.

Terminal alternative (inside this project folder):

```sh
sh mvnw spring-boot:run
```

On Windows use `mvnw.cmd spring-boot:run`. To test and package:

```sh
sh mvnw test
sh mvnw package
java -jar target/just-in-transit-0.0.1-SNAPSHOT.jar
```

## What is included

- Mobile-friendly Thymeleaf homepage with start address, destination, travel date, arrival deadline, entrance selection, walking minutes, and configurable buffers.
- VIA GTFS Schedule reader, calendar exceptions, and service times beyond midnight.
- GTFS Realtime TripUpdates reader, 30-second fetch cache, feed timestamps, and clear scheduled/live labels for each stop event.
- Up to three departure options for the chosen corridor. Arrival-mode home starts prefer matching wait preferences and buffers, shorter waiting, then later departures; departure-mode and stage starts prefer matching preferences among on-time candidates, then earliest remaining arrival.
- Transfer margin, destination arrival margin, and a backup card showing the next matching 93 departure, arrival at your destination including the final walk, and minutes early or late.
- Explicit address lookup, nearby stop choices in the correct direction, and pedestrian walking estimates with manual overrides.
- VIA service advisories filtered to the selected journey, including backup buses; clear unavailable/stale alert states.
- Next-bus board for the two boarding stops; optional plan refresh every 30 seconds while the page is open.
- “I’m leaving” starts a pinned journey with walking, riding, transfer, and arrival confirmations. Browser refresh preserves the selected trip IDs and recalculates only unfinished events.
- “I missed the 93” searches from the transfer stop for the next usable bus, including its final walk and any lateness.
- Journey-first phone layout; the setup form is behind **Edit commute** after a plan exists. Saved settings produce a departure plan when you open the homepage.
- Optional password-protected HTTPS phone mode with one owner account.
- Optional local saved commute, deletion, and links to check walking directions in Google Maps.
- Local H2 persistence by default; optional MySQL configuration.

## Route and calculation

| Leg | Verified GTFS stop IDs |
| --- | --- |
| 24 toward Centro Plaza | `81799` E. HOUSTON & MITTMAN → `76779` MARTIN & LEONA |
| Walk to the 93 | `76779` → `76773` MARTIN OPP LEONA |
| 93 toward Fiesta Texas | `76773` → `83463` employee entrance, `82459` main entrance, or `83469` opposite employee entrance |

These are the original default stops. Address lookup can select other eligible stops on the 24 before Martin & Leona and on the 93 after Martin Opposite Leona. The planner verifies that boarding and alighting stops occur in that order on the same scheduled trip. It does not treat the opposite direction as a match.

```text
Leave home = first bus boarding time − walk to stop − boarding buffer
Transfer margin = second bus boarding time − first bus arrival − transfer walk
At destination = final bus arrival + walk to clock-in location
Arrival margin = arrival deadline − destination arrival
```

For your example (7:15 first bus, 7:45 transfer arrival, 7:55 second bus, 8:30 Six Flags), walking estimates of 8 / 3 / 10 minutes and a 3-minute first-stop buffer imply leaving at **7:04**, a **7-minute transfer margin**, and arriving at the clock-in location at **8:40**. This explains your example; it is not a verified current departure.

“Buffers met” means the estimates satisfy your chosen margins. When either critical transfer event lacks a direct live prediction, the UI says **Buffers met · estimated** and **Connection unconfirmed**. It is a heuristic, not a probability or guarantee. Times use `America/Chicago`, including daylight saving time.

The search checks adjacent service days for overnight trips and limits transfer waiting after walking to 90 minutes. Ordinary arrival-mode starts consider first buses no more than eight hours before the deadline and arrivals up to six hours after the later of now/deadline, with an earliest-arrival fallback when late. Departure mode considers leave times after the selected time and ranks earliest arrivals. At-stop/on-board starts search remaining arrivals within six hours of now. One connection is selected per first bus. This is not a citywide transit-network optimizer.

## Data behavior and limits

Official data: https://www.viainfo.net/developers-resources/

- Schedule: `https://www.viainfo.net/BusService/google_transit.zip`
- TripUpdates: `http://gtfs.viainfo.net/tripupdate/tripupdates.pb`
- Schedule refresh: six hours, with a local ZIP cache if a later download fails.
- Live refresh: at most once per 30 seconds per server instance.
- Live estimates require a feed timestamp no older than two minutes and no more than 30 seconds in the future. If a trip supplies its own timestamp, that must be fresh too.
- A stale feed, stale trip, or event without a usable prediction becomes a clearly labeled **Scheduled** time. `NO_DATA` stops inherited delays until a later prediction. This fallback is not evidence the bus is on time.
- Fresh canceled trips and skipped stops are excluded. Unsupported trip relationships are not used. Differential snapshots are rejected; this prototype expects complete snapshots.
- When VIA supplies arrival but not departure at a boarding stop, the UI explicitly labels it **Live arrival · boarding proxy**. This is not a confirmed departure estimate.
- Delay-only stop events and trip-wide delay are supported. A stop delay carries through omitted downstream stops; absolute times are compared with their static event time to derive a delay. Later scheduled updates replace it, `NO_DATA` clears it, and `SKIPPED` does not interrupt it. A repeated stop ID needs an unambiguous sequence. Carried estimates and arrival-only boarding proxies are explicitly labeled. Reported uncertainty is displayed, not converted into a probability. Vehicle positions are displayed separately on the map. They never infer arrival times or boarding. Frequency-based service is not implemented. Service alerts remain separate advisories.
- Future travel usually uses the static schedule. There is no implied live prediction for tomorrow.
- The public realtime endpoint uses HTTP. Its availability, freshness, and coverage depend on VIA. Endpoint URLs can be changed in `application.properties`.
- VIA may change stop IDs, route patterns, or feed formats. Recheck the official data if matching service disappears.

## Address and walking behavior

Address selection now changes the boarding and alighting stops used by both the planner and the next-bus board. It stays within the selected **24 → 93 or 93 → 24 corridor with the Martin/Leona transfer**; it is not a citywide route optimizer.

1. Set the travel date. To find a particular intersection, open **Find a different 24 stop**, enter **Houston & Mittman** or **Mittman**, and click **Search 24 stops**. The search uses actual VIA stop names and IDs; it does not depend on your address match. It ignores punctuation, street-word order, and common direction/street abbreviations. Only stops serving the supported transfer in the correct direction on that date are offered. Check the stop ID and map before selecting. The 93 has its own stop search.
2. For automatic walking estimates, search each complete street address or specific place, then choose the exact result. Street/area-only matches and candidates with a different house number are not selectable as exact addresses. If none match, one message offers a map pin or manual walking setup. A broad result can center the map, but never becomes a confirmed location without your explicit pin confirmation. Duplicate exact results at the same point are combined. The selected location remains visible with a map link so you can check your entrance. A named place may still point to a building centroid: verify the map.
3. The app shows **every eligible stop within 2 km**, ordered by straight-line distance, instead of limiting the list to five. It filters on the service calendar, pickup/drop-off permissions, and stop order relative to the transfer. Opposite-direction stops are not offered as equivalents. An unsupported address shows a clear no-nearby-stop result.
4. Choose a stop. A pedestrian route calculation fills the corresponding walking-minutes field, rounding up. The walking pace is 4.5 km/h; the estimate is not a guarantee of public access, gate availability, or crossing delay.
5. Calculate the transfer walk separately if desired. All three walking times remain editable. Failed address/endpoint walking lookups leave the new walking field empty so you must enter a checked time; they do not substitute a straight-line walk. A failed transfer lookup keeps the existing transfer minutes and says so.
6. Changing an address invalidates its old match. Changing the date refreshes nearby stop choices in address mode. In manual mode, verify your selected stops and walking times yourself; submitted stops are checked against the selected service date when the schedule is available.

Stop-name search can find eligible stops outside the 2 km nearby list. If you select one outside that radius, or have not resolved an address, **Use with manual walking times** switches only that end of the commute to manual mode and clears its walking minutes for you to enter. A confirmed point at the other end is preserved. It never replaces your home address with the bus stop’s coordinates. Availability still depends on a usable VIA schedule; this is not a citywide stop or route search.

The default personal-prototype services are the **U.S. Census geocoder** for numbered street addresses, **Photon** (`https://photon.komoot.io/api/`) for place names and fallback searches, and **Valhalla** (`https://valhalla1.openstreetmap.de/route`) for pedestrian routing. Photon and Valhalla use OpenStreetMap data. Census uses address ranges and returns estimated street positions; each such result requires entrance confirmation on the map. They require no API key in the default configuration. These public demo services have fair-use limits and no availability guarantee. Lookups are explicit button actions, rate-limited per provider, and cached in memory for up to 24 hours (maximum 256 responses). Neither planning nor automatic refresh calls them. Search is bounded to the San Antonio area.

Stop-name searches use the VIA schedule already loaded by the app and do not call the geocoders or walking provider. Clicking **Search address** sends a numbered address to the U.S. Census geocoder first. Place names, or fallback requests after an address match fails, go to Photon. These disclosures are also shown beside the form. Selected coordinates are sent to Valhalla when calculating a walk. Map links share their endpoints with Google only when opened. Lookup URLs and responses are not written to application logs. The application cache is cleared when you delete the saved commute or restart the app; that does not delete third-party provider logs.

For a self-hosted deployment or a different compatible endpoint, set:

```text
JIT_ADDRESS_GEOCODER_ENABLED=true
JIT_ADDRESS_GEOCODER_URL=https://geocoding.geo.census.gov/geocoder/locations/onelineaddress
JIT_GEOCODER_URL=https://your-photon-server/api/
JIT_WALKING_URL=https://your-valhalla-server/route
JIT_MAP_CLIENT_ID=your-identifying-app-name
JIT_MAPS_ENABLED=true
```

These URLs are administrator configuration, never accepted from a browser request. Use `JIT_MAPS_ENABLED=false` to disable Census, Photon, and Valhalla lookups. This does not disable the separate browser map tiles or VIA feeds. Before distributing a public app, arrange appropriate hosting/capacity and follow the providers' terms; Valhalla's demo maintainers request identification and notification for published apps.

Set `JIT_ADDRESS_GEOCODER_ENABLED=false` to disable only the Census lookup. Its endpoint expects the Census locations/onelineaddress response shape, not the Photon format. No new key or account is needed for the default address service.

Provider documentation:
- [Census API](https://geocoding.geo.census.gov/geocoder/Geocoding_Services_API.html)
- [Census geocoder and interpolated coordinate accuracy](https://www.census.gov/programs-surveys/geography/technical-documentation/complete-technical-documentation/census-geocoder.html)
- https://github.com/komoot/photon (demo terms)
- https://github.com/komoot/photon/blob/master/docs/api-v1.md (search API)
- https://valhalla.github.io/valhalla/ (demo terms)
- https://valhalla.github.io/valhalla/api/route/api-reference/ (pedestrian routing)

## Backup buses and service advisories

Each primary option includes the next later usable second bus (93 to Work or 24 to Home) serving the **same selected destination stop**, where available. Fresh cancellations and skipped boarding/alighting stops are excluded. Its destination time includes your final walk and can be later than your deadline. Any lateness is rounded up to minutes; it is never rounded into an on-time result. Backups are searched within the service days already loaded for that plan, not an unlimited next-day search. They are contingency estimates, not a claim that you will catch them after an unknown disruption.

Service alerts use `http://gtfs.viainfo.net/alert/alerts.pb` (override with `VIA_ALERTS_URL`). They are fetched at most once per minute. A feed older than ten minutes, missing its timestamp, more than 30 seconds in the future, or affected by a download failure is **unconfirmed**, not an all-clear. Last downloaded notices may remain visible with that warning.

Advisories match active periods and route, agency, route type, direction, trip/service date, and stops along the displayed legs, including backups. Fields inside one GTFS selector are combined with AND, and separate selectors with OR. Before a plan exists, the section shows matching broad route/selected-stop notices for the chosen date; trip-specific filtering is available after planning. Alert text is escaped and only HTTP(S) notice links are displayed.

Alerts are advisory: a closure notice may invalidate an otherwise timed connection. The app warns you to check it and does not automatically reroute or infer cancellation from free text. Unsupported differential feeds are rejected. The map now displays vehicle reports; automatic boarding detection and GPS-based arrival inference remain outside this update. The footer includes **Data provided by VIA Metropolitan Transit** and OpenStreetMap attribution.

## Proving the predictions are useful

1. Open the app during the morning commute and expand **Data status**. Confirm a recent feed timestamp.
2. Plan the trip and check which individual events say **Live estimate**, **boarding proxy**, or **Scheduled**. A connected feed alone does not prove coverage for your buses.
3. Record the route, stop, prediction observation time, predicted arrival, and actual observed arrival/departure. Take observations about 5, 10, and 15 minutes before the bus reaches the stop.
4. Repeat across several commute days. Compare prediction error and missing/stale coverage separately for the 24 and 93. Record whether the actual transfer succeeded and the actual walking time.
5. Adjust your heuristic buffers from that evidence before considering calibrated risk percentages. Opt-in completed-trip reports can help review overall timing, but they do not collect repeated prediction observations or train a model.

The automated tests check parsing and decision logic. They do **not** establish real-world prediction accuracy. A real feed snapshot can demonstrate readable data, but repeated observed trips are needed to assess reliability.

There is also an optional integration test for captured VIA data. Save `google_transit.zip` and a valid daytime `tripupdates.pb` in one folder and run:

```sh
sh mvnw test -Dvia.integration.dir=/absolute/path/to/your/feed-folder
```

It checks parsing and prints how many boarding events match live data at the snapshot's own timestamp. Without that option, tests use small offline fixtures and skip the captured-feed test.

## Saved data and local use

The default app still binds to `127.0.0.1` without a login for local use. A startup guard refuses an unprotected non-loopback bind. The optional `phone` profile adds one owner login; this remains a single-owner application with one saved commute, not a multi-user service.

Clicking **Save commute & find departure** stores one usual commute in `data/commutes.mv.db` on the server running the app. **Find my departure** keeps an unsaved plan only in the session. **Start this commute** also saves an expiring journey checkpoint containing its settings, points and confirmed progress for same-browser recovery. **Delete saved commute** deletes the stored record, clears this session’s journey and lookups, and does not securely erase database history or backups. Other browser sessions keep their own in-memory journey until ended or expired. Forms, address/walking lookups, and journey changes use CSRF protection. The read-only live-map endpoint uses GET and is private in the phone profile, with no-store response headers.

### Follow your commute

1. On the travel date, choose **I’m leaving · start this commute** on a departure option. Start when you actually leave; the planner rechecks whether the remaining walk can reach that 24.
2. Tap **I’m on the 24**, **I’m off the 24**, **I’m at the 93 stop**, **I’m on the 93**, **I’m off the 93**, then **I’ve arrived** as you go. These are your confirmations, not automatic GPS detections.
3. Refresh keeps the chosen trip IDs and service dates even after the leave-home time passes. Confirmed events stay fixed. Only unfinished events receive fresh predictions. Cancellations, skipped stops, missing schedules, and passed estimates produce warnings instead of deleting the journey.
4. Once at the 93 stop, **I missed the 93 · find another** excludes the missed trip and finds the earliest remaining usable departure to your selected destination within six hours, including next-day service where relevant. It can select a late arrival and labels it accordingly. If none is available, progress is preserved and **Check for the next 93** retries. No replacement is silently chosen during ordinary refresh.
5. **Finish commute** or **End commute and return to planning** ends the active journey. The last settings remain in the session for another search. If you miss the 24, confirm **I’m at the 24 stop**, then use **I missed the 24** to find another connection from that stop. This action uses your confirmation, not inferred GPS position.

Active journeys expire 18 hours after starting. An expiring database checkpoint and random browser cookie allow explicit resume after session expiry or server restart. A different browser without the cookie cannot resume it. Completed progress stays fixed; future estimates refresh on resume. Finishing, ending or discarding removes the checkpoint. Signing out clears the cookie. Expired checkpoints are removed when accessed or when a new checkpoint is saved. Database history/backups are not securely erased. There is no permanent journey history or cross-device synchronization.

The map refresh defaults on and runs every 30 seconds while visible. It refreshes map data and active-journey details with GET requests, preserving the mounted map and location watch. Returning to the visible page refreshes current reports. It never starts a new search from home. The main selected recommendation now refreshes every 30 seconds while visible; editing invalidates it. The separate full-plan re-search remains opt-in and pauses while editing. There are no leave-home or missed-connection push notifications while the app is closed.

### Protected phone access

The `phone` profile requires HTTPS and a password supplied outside the source code. No live server, certificate, or hosting account is provisioned by this update.

Set these environment variables in your run configuration:

```text
SPRING_PROFILES_ACTIVE=phone
JIT_USERNAME=victor
JIT_PASSWORD=<your unique 16–72 character password, at most 72 UTF-8 bytes>
```

Choose one deployment arrangement:

- **An existing HTTPS reverse proxy on the same machine:** keep the app’s default `127.0.0.1:8081` bind. The proxy must terminate TLS, strip untrusted forwarded headers, and set the HTTPS forwarding headers itself. Set `JIT_FORWARD_HEADERS=framework` only with that trusted proxy in place. Open the proxy’s HTTPS address from your phone.
- **Direct HTTPS on your private network:** supply a certificate trusted by the phone in a PKCS#12 keystore, set `SERVER_SSL_ENABLED=true`, `SERVER_SSL_KEY_STORE=file:/absolute/path/to/your-keystore.p12`, `SERVER_SSL_KEY_STORE_PASSWORD`, and `SERVER_SSL_KEY_STORE_TYPE=PKCS12`. Set `JIT_BIND_ADDRESS` to the server’s private LAN address. Open `https://<that-address>:8081` (the certificate must cover the address/name used). Keep forwarded headers disabled.

Start with `sh mvnw spring-boot:run`, or build with `sh mvnw package` and run the JAR using the same environment variables. Sign in at `/login`. The profile redirects HTTP to HTTPS, uses secure HttpOnly session cookies, protects all commute and map routes, and clears the session on sign-out. Plain HTTP phone access will not work in this profile. Do not expose the application port directly behind a proxy that trusts forwarded headers. Public deployment still requires the host’s normal TLS, ingress restrictions, login rate limiting, backups, and maintenance.

## Optional MySQL profile

Start with H2. If you already have a local MySQL server, create a database and a dedicated user, then supply their values as environment variables in your STS Run Configuration:

```text
SPRING_PROFILES_ACTIVE=mysql
JIT_DB_URL=jdbc:mysql://localhost:3306/just_in_transit
JIT_DB_USERNAME=your_database_user
JIT_DB_PASSWORD=your_database_password
```

Create the database yourself before starting with this profile. Credentials are not bundled. Hibernate creates/updates the tables for development. Existing H2 data is not automatically migrated. For deployment, use controlled database migrations and proper account isolation.

## Code map

| File | Responsibility |
| --- | --- |
| `HomeController.java` | Homepage, validation, planning and save/delete actions |
| `CommuteForm.java` | User inputs and validation bounds |
| `Corridor.java` | Work/Home route order, transfer stops and separate saved-setting identities |
| `TripReport.java`, `TripHistory.java`, `HistoryController.java` | Optional completed reports and deletion |
| `OfflineCardController.java`, `offline-card.js`, `offline.js`, `sw.js` | Private snapshot, opt-in device copy and public offline shell |
| `CorridorStops.java` | Direction/calendar-aware eligible stops, intersection search, and nearby stops |
| `MapService.java`, `MapController.java` | Explicit address search, pedestrian routes, caching and request validation |
| `AlertStore.java` | Service-alert parsing, relevance, freshness and safe display |
| `ScheduleStore.java` | Static GTFS download, cache, CSV parsing and calendars |
| `RealtimeStore.java`, `LiveSnapshot.java` | Protobuf normalization, freshness, GTFS delay propagation and event matching |
| `ActiveJourney.java`, `JourneySession.java` | Pinned journey, progress, recovery, expiry and stale-action protection |
| `CommutePlanner.java` | Route matching, walks, buffers and ranking |
| `TransitModels.java` | Data records and time handling |
| `SavedCommute.java`, `CommuteRepository.java` | Local persistence |
| `SecurityConfig.java`, `AccessSettings.java` | CSRF, optional owner login, HTTPS and safe bind validation |
| `templates/home.html`, `static/app.css`, `static/app.js` | Interface and optional refresh |

Built with Spring Boot 4.1.1, Java 17, Thymeleaf, Spring Data JPA, H2, GTFS Realtime bindings, and Apache Commons CSV.

## This update's validation

Offline tests cover address/stop changes, wrong-direction and inactive stops, map-response parsing, missing walking estimates, backup lateness, canceled backups, alert relevance/expiry/direction/service date, escaped notice content, map endpoint validation/CSRF, and the rendered backup and advisory interface. Captured VIA integration remains opt-in and is not a real-world accuracy test.

## Updating your existing project

Unzip the updated project into a separate folder first. Copy its project files into your original `just-in-transit` folder while keeping your existing `data/` and `.git/` directories. The delivery ZIP excludes local database contents, downloaded feed caches, and build output. Back up your existing data before running an updated development schema.

Validation for the October 12 update: 45 Java tests passed; the optional captured-VIA integration test was skipped because no capture was supplied. `mvn package` succeeded. DOM interaction checks covered location selection, walking estimates, edited-address invalidation, late responses after manual overrides, and failed lookups. Photon and Valhalla responded to public sample requests. A full browser visual check could not be completed because the browser binary download failed in the build environment.

## October 13 active-commute update — validation

- **29 isolated logic checks passed** against the actual Java domain classes: pinned trips after departure, future-event refresh, missed-93 recovery and late arrival, unavailable service, confirmation times, session separation/expiry, duplicate submissions, access configuration, and GTFS delay rules.
- Core classes and the security configuration compiled against locally cached Spring libraries (Boot 4.1.0 / Security 7.1.0), not the project’s exact Maven-resolved dependency set. The isolated runner supplied only lightweight JUnit annotation/assertion helpers; it did not run Spring or protobuf parsing.
- The actual Thymeleaf home template rendered successfully in plan, active, waiting, arrived, initial, and validation-error states; the login template also rendered. Synthetic fixture values were used.
- **30 browser checks passed** on those rendered fixtures: no horizontal overflow at 320, 390, 768, and 1280 pixels; journey content near the top on phones; collapsed editor and working Edit shortcut; active refresh uses GET rather than replanning. No browser JavaScript errors were observed. These checks use rendered fixtures, not a running Spring server.
- The full `sh mvnw test` / packaged application could not be verified here: Maven Central’s host could not resolve. Existing tests and new `JourneyLogicTests`, `LiveTimingTests`, `JourneyWebTests`, `ProtectedAccessTests`, and protobuf regression cases are included for execution on your machine. Bundled reports from earlier runs are excluded from the delivered ZIP.
- No live VIA commute accuracy, HTTPS deployment, or phone field test is claimed. Run `sh mvnw test` and `sh mvnw package` before deployment, then verify a real commute alongside VIA’s rider information.

GTFS behavior reference: https://gtfs.org/documentation/realtime/feed-entities/trip-updates/
Spring Security references: https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html and https://docs.spring.io/spring-security/reference/servlet/exploits/http.html

### Clean shareable export

Run `python3 scripts/export_project.py` from the project. It creates a source-only ZIP under `dist/`, using a file allowlist. The archive excludes `.git/`, `data/`, `.env`, IDE settings, build output, captures, and caches. It never deletes or modifies those local directories.

## Stop-selection update — validation

- Eight focused Java checks passed using the actual `CorridorStops`, `MapService`, and `TransitModels` classes with cached Java dependencies and a lightweight assertion runner. They cover Houston/Mittman name variants and ID search, direction/calendar/pickup restrictions, finding a seventh nearby stop, the exact 2 km boundary, search independent of coordinates, empty matches, and street-only versus precise geocoder candidates.
- Modified controller and service code compiled against the locally cached Spring/Jackson libraries. Those cached versions are not the exact Maven dependency set in `pom.xml`.
- The real Thymeleaf template rendered with synthetic fixtures. Thirteen browser checks passed with mocked map/stop responses: search and selection, date in requests, numbered-address mismatch rejection, all nearby stops, walking estimate, explicit manual fallback without moving the address pin, missing address guidance, stale-result rejection, date invalidation, and no horizontal overflow at 320/390/768/1280 pixels. The 390-pixel result was visually reviewed; no browser JavaScript errors were observed.
- `StopSelectionTests` and two additional endpoint validation/CSRF cases in `BoundaryWebTests` are included for Maven. The full Maven suite could not run here because the exact Spring Boot 4.1.1 parent and project dependencies are not cached. Run `sh mvnw test` on your Mac before starting the updated app. Browser fixtures and mocked responses do not verify a live VIA feed or real walking accuracy.

## Live map and countdowns

1. The map shows the selected stops and recent VIA buses serving those stops in the supported 24 → 93 direction. Tap a bus for its route, destination, vehicle ID, and report time; **Bus reports and times** provides the same information as text.
2. Tap **Find me** to request browser location access. A blue dot and accuracy circle show the device's reported location. **Use my location as start** is available only for a San Antonio reading no older than 30 seconds with reported accuracy of 100 m or better. Check the point before using it. Coarse, denied, unavailable, or stale location readings have explicit messages. Phone access requires HTTPS; localhost is suitable for development. No location is requested on page load.
3. If the address lookup cannot find your house, choose **Pin starting point** or **Pin destination**. Move the map until the entrance sits under the +, then choose **Use this point**. This confirms only that end of the commute, finds nearby supported stops, and clears its old walking estimate. Choose a stop to calculate the walk. You can keep manual walking minutes if a route lookup fails. Changing the address again invalidates that pin.
4. Find a departure. The first option's countdowns appear above the map: leave time, 24 boarding, 93 boarding, and arrival at the destination. **Show this departure on the map** switches the preview to another offered option. The leave time subtracts the entered walk and boarding buffer from the 24's estimated boarding time. Seconds on the clock do not imply second-level prediction accuracy. Tomorrow's plan is labeled with its date; bus markers remain current reports.
5. Countdown refresh keeps the same offered trip IDs and service dates. Preview offers expire after 18 hours, session loss, or when another plan replaces them. Starting still revalidates current feasibility. Editing settings clears the preview and asks you to plan again. Starting a commute switches the map to the active journey; completed steps stop counting down. Missing/canceled buses and expired estimates are labeled unavailable/unconfirmed. A passed estimate says **Check now**, never a negative countdown or a promise that a bus is still waiting.
6. A star means a vehicle report explicitly matches the trip ID **and service date** in your plan. Reports without service dates can appear as route vehicles but are not confidently highlighted as your selected bus. Completed or missed legs are not highlighted. Positions cannot confirm that you boarded; continue using the existing progress buttons.
7. The map refreshes every 30 seconds while visible; the displayed clocks tick each second. Active-journey details refresh without replacing the map. Feed and vehicle timestamps must both be within 90 seconds to display a fresh bus. Older/missing timestamps appear gray and unconfirmed; reports older than ten minutes are omitted. A request failure immediately marks remaining displayed reports and countdowns unconfirmed. Countdown validity also respects the trip-update timestamp, even when the feed envelope is newer.
8. **Stop locating me** removes the location dot and stops the watch. Leaving/hiding the page pauses the watch; returning resumes only a watch you explicitly started. There is no background location history, automatic route recalculation from movement, or closed-app notification. **Use this point** places coordinates in the commute form; planning keeps them in the session and Save persists them as part of the existing commute settings.

### Map services and configuration

Leaflet **1.9.4** and its license are bundled in `static/vendor/leaflet/`; no map-library CDN or API key is required. Default tiles are ordinary browser requests to `https://tile.openstreetmap.org/{z}/{x}/{y}.png`, with OpenStreetMap attribution and normal HTTP caching. Only the viewed area is requested; there is no tile prefetching or offline-map download. Tiles reveal the viewed area and browser IP to the provider. The map has a text alternative and the timetable remains usable if tiles or JavaScript are unavailable.

- `VIA_VEHICLES_URL` overrides the server-side vehicle feed URL. Default: `http://gtfs.viainfo.net/vehicle/vehiclepositions.pb`, fetched at most once per 30 seconds across sessions. `via.enabled=false` disables VIA network access, including vehicles.
- `JIT_MAP_TILE_URL` overrides the tile template (HTTPS or a same-origin path). `JIT_MAP_TILE_CREDIT` adds plain-text provider attribution. Keep the default OSM attribution when using OSM-derived data and follow the selected provider's terms. Public or larger deployments should arrange suitable map hosting rather than assume capacity on community services.
- `JIT_MAPS_ENABLED` controls Census/Photon/Valhalla address and walking requests. Map pin selection and the browser location watch do not depend on those providers.

Developer files: `VehicleStore` handles protobuf positions and shared fetching; `LiveMapData` handles direction matching, freshness, and countdown presentation; `LiveMapController` exposes a private read-only status endpoint; `live-map.js` handles map layers, clocks, optional location, pins, and updates. Bus positions do not modify the saved commute or the selected trips.

References: https://www.viainfo.net/developers-resources/ ; https://gtfs.org/documentation/realtime/feed-entities/vehicle-positions/ ; https://leafletjs.com/ ; https://operations.osmfoundation.org/policies/tiles/ ; https://developer.mozilla.org/en-US/docs/Web/API/Geolocation_API

### Live-map validation

- Maven packaging succeeded. Full Maven tests with the project's exact Spring Boot 4.1.1 dependencies: **108 tests, 107 passed, one optional captured-feed test skipped, zero failures/errors**. Includes vehicle parsing/freshness, wrong-direction exclusions, service-date matching, countdown calculations/expiry/cancellation/progress, session isolation, endpoint validation, and phone-profile authentication. This supersedes the earlier environment limitations noted above.
- Seventeen browser checks passed against real Spring-rendered templates with mocked transit/location responses and synthetic map tiles. Checked ticking/expired countdowns, stale bus styling, denied/coarse/precise location, GPS start selection, entrance pins, walking lookup integration, failure handling, active refresh without navigation/resetting the map, and layout at 320/390/768/1280 pixels. No browser JavaScript errors; the phone layout was visually reviewed. These checks did not request OSM tiles from a headless browser.
- A real VIA vehicle snapshot was downloaded and parsed: 424 feed entities, 210 usable scheduled-trip positions, and four vehicles matching the default supported stop pairs (two 24s and two 93s) against the uploaded schedule cache. All four reports were fresh at the snapshot timestamp. This verifies feed parsing/matching for that capture, not future coverage, prediction accuracy, or a field-tested commute.
- Phone GPS accuracy, real walking access, and a deployed HTTPS phone session still require testing on your device. No hosting or certificate was provisioned.

## Clearer setup and address recovery

The initial screen puts three numbered setup sections beside the map on desktop, and before it on phones. Once a plan is ready, departures take priority. Stop searches, transfer buffers, trip breakdowns, bus reports, and privacy details expand when needed. Confidence warnings remain visible with each departure. Each address has its own map button; the starting point also has a location shortcut.

A house number missing from Photon cannot be fixed by changing capitalization. Search now displays one explanation instead of repeated disabled street results. **Set entrance on map** opens the general search area with explicit instructions to move the crosshair to the entrance; it does not claim to have found the building. A pin works without geocoding, and manual stop/walking setup works without geocoding or pedestrian routing. Stop availability still needs the VIA schedule. Use **Save these settings** after confirming the points and walking times.

The existing `locationMode` field remains compatible with `manual` and `address` and also supports `origin` or `destination` for an independently confirmed endpoint. The location-mode extension required no schema change in that earlier release; the October 14 journey update adds settings columns and a trip-report table as described above. Server validation checks the resolved label, coordinates, nearby radius, and supported stop direction for each mapped end. Walking links use confirmed coordinates instead of sending the unresolved address text back to a map service. The browser allows up to 25 seconds for an address search across both services; other map lookups time out after 15 seconds. Provider requests have their own shorter timeouts. Recovery actions remain available.

Validation for this update: **112 Maven tests: 111 passed, one optional captured-feed test skipped, zero failures/errors**. Four new web tests cover independent endpoint saving/restoration, walking-link coordinates, incomplete/changed points, and the 2 km boundary. **30 browser checks** passed on real Spring-rendered templates with mocked search/transit/location responses and synthetic tiles, including the repeated Nolan Street case, wrong house numbers, pin confirmation, mixed endpoint setup, search/walk outages, duplicate matches, invalid-field reveal, countdowns, GPS permission handling, and phone/desktop overflow checks. Desktop and phone layouts were visually reviewed. This verifies the workflow, not house-number coverage in the external map database.

## Numbered-address lookup repair

The previous layout update retained Photon as the only geocoder; it improved recovery but could not supply missing house numbers. This update changes the lookup itself. For numbered addresses, it requests current U.S. Census address ranges first, adds San Antonio context to short local queries, and falls back to Photon when necessary. Place-name queries continue to use Photon directly. House-number mismatches, invalid/nonlocal coordinates, malformed provider responses, and wrong-state responses are rejected. A provider failure is not presented as proof that a house number is missing.

A Census address match is **not a verified building entrance**. Selecting it centers the map at street level and displays an explicit accuracy note. The form's coordinates and walking estimates remain empty until **Use this point** confirms the entrance. Canceling or changing the address prevents the previous candidate from being used. Map confirmation controls now appear above the map. Manual walking setup stays available if the map library fails to load.

Validation: **121 Maven tests: 120 passed, one optional captured-feed test skipped, zero failures/errors**. Nine new tests cover source selection, local query context, provider fallback/failure, cache reuse, rejected coordinates/numbers, and administrator disable controls. **35 browser checks** passed against rendered Spring templates with transit/location mocks and synthetic map tiles; the Census browser case uses a real captured result returned through the updated Java MapService. A live call through production MapService resolved the public Central Library address at **600 Soledad St, San Antonio, TX**, requiring map confirmation. The user's residential address has not been sent to the new provider or verified; that test is pending explicit permission. This validation does not claim universal address coverage or precise entrance coordinates.
