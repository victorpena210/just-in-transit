# Just in Transit

A local commute planner for San Antonio's VIA 24 → 93 route. Enter where you start, where you clock in, your arrival deadline, and walking estimates. The app compares departures using VIA's schedule and fresh live predictions when available.

## Start in Spring Tool Suite / Eclipse

1. Unzip this project into a new folder. Keep your original project as a backup.
2. Choose **File → Import → Maven → Existing Maven Projects**.
3. Select the extracted `just-in-transit` folder containing `pom.xml`, then click **Finish**. If Eclipse already has a project with this name, remove that project from the workspace without deleting its files, then import this one.
4. Set the project JRE to **Java 17 or newer**. Let Maven download dependencies; use **Maven → Update Project** if needed.
5. Right-click `JustInTransitApplication.java` → **Run As → Spring Boot App**.
6. Open **http://localhost:8081**.
7. Set your travel date and arrival deadline. To use addresses, choose **Find stops using my addresses**, click **Find starting point** and **Find destination**, and select the correct results. Choose a nearby stop for each location; the app fills in pedestrian walking estimates. Check the exact entrance and adjust walking minutes for crossings or gates. Click **Find my departure**.
8. If map services are unavailable, choose **Select stops and walking times myself**. The planner remains usable with manual settings.

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
- Up to three departure options for the fixed 24 → 93 commute, ranked first by whether your buffers are met, then by latest departure.
- Transfer margin, destination arrival margin, and a backup card showing the next matching 93 departure, arrival at your destination including the final walk, and minutes early or late.
- Explicit address lookup, nearby stop choices in the correct direction, and pedestrian walking estimates with manual overrides.
- VIA service advisories filtered to the selected journey, including backup buses; clear unavailable/stale alert states.
- Next-bus board for the two boarding stops; optional plan refresh every 30 seconds while the page is open.
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

“Buffers met” means the estimates satisfy your chosen margins. It is a heuristic, not a probability or guarantee. Times use `America/Chicago`, including daylight saving time.

The search considers this service day and the previous one for overnight GTFS trips, at most eight hours before the deadline, at most 90 minutes of transfer waiting after walking, and arrival within six hours before the deadline. One connecting bus is selected per first bus, preferring buffers met and then less transfer waiting. It is not a global transit-network optimizer.

## Data behavior and limits

Official data: https://www.viainfo.net/developers-resources/

- Schedule: `https://www.viainfo.net/BusService/google_transit.zip`
- TripUpdates: `http://gtfs.viainfo.net/tripupdate/tripupdates.pb`
- Schedule refresh: six hours, with a local ZIP cache if a later download fails.
- Live refresh: at most once per 30 seconds per server instance.
- Live estimates require a feed timestamp no older than two minutes and no more than 30 seconds in the future. If a trip supplies its own timestamp, that must be fresh too.
- A stale feed, stale trip, missing stop update, or `NO_DATA` becomes a clearly labeled **Scheduled** time. This is a fallback, not evidence the bus is on time.
- Fresh canceled trips and skipped stops are excluded. Unsupported trip relationships are not used. Differential snapshots are rejected; this prototype expects complete snapshots.
- When VIA supplies arrival but not departure at a boarding stop, the UI explicitly labels it **Live arrival · boarding proxy**. This is not a confirmed departure estimate.
- Delay-only stop events are supported. This first version does not propagate delays between stops, infer missing stop times from GPS, apply trip-wide delay, process vehicle positions, or model frequency-based service. Service alerts are handled separately as advisories.
- Future travel usually uses the static schedule. There is no implied live prediction for tomorrow.
- The public realtime endpoint uses HTTP. Its availability, freshness, and coverage depend on VIA. Endpoint URLs can be changed in `application.properties`.
- VIA may change stop IDs, route patterns, or feed formats. Recheck the official data if matching service disappears.

## Address and walking behavior

Address selection now changes the boarding and alighting stops used by both the planner and the next-bus board. It stays within the **24 → 93 corridor with the Martin/Leona transfer**; it is not a citywide route optimizer.

1. Set the travel date, search each address explicitly, and choose the matching location. Check that a building centroid or a similarly named place is not being mistaken for your entrance.
2. The app finds up to five eligible stops within 2 km straight-line distance. It filters on the service calendar, pickup/drop-off permissions, and stop order relative to the transfer. Opposite-direction stops are not offered as equivalents. An unsupported address shows a clear no-nearby-stop result.
3. Choose a stop. A pedestrian route calculation fills the corresponding walking-minutes field, rounding up. The walking pace is 4.5 km/h; the estimate is not a guarantee of public access, gate availability, or crossing delay.
4. Calculate the transfer walk separately if desired. All three walking times remain editable. Failed address/endpoint walking lookups leave the new walking field empty so you must enter a checked time; they do not substitute a straight-line walk. A failed transfer lookup keeps the existing transfer minutes and says so.
5. Changing an address invalidates its old match. Changing the date refreshes nearby stop choices in address mode. In manual mode, verify your selected stops and walking times yourself; submitted stops are checked against the selected service date when the schedule is available.

The default personal-prototype services are **Photon** (`https://photon.komoot.io/api/`) for geocoding and **Valhalla** (`https://valhalla1.openstreetmap.de/route`) for pedestrian routing, using OpenStreetMap data. They require no API key in the default configuration. These public demo services have fair-use limits and no availability guarantee. Lookups are explicit button actions, rate-limited per provider, and cached in memory for up to 24 hours (maximum 256 responses). Neither planning nor automatic refresh calls them. Search is bounded to the San Antonio area.

An address is sent to Photon only after you click **Find**. Selected coordinates are sent to Valhalla when calculating a walk. Map links share their endpoints with Google only when opened. Lookup URLs and responses are not written to application logs. The application cache is cleared when you delete the saved commute or restart the app; that does not delete third-party provider logs.

For a self-hosted deployment or a different compatible endpoint, set:

```text
JIT_GEOCODER_URL=https://your-photon-server/api/
JIT_WALKING_URL=https://your-valhalla-server/route
JIT_MAP_CLIENT_ID=your-identifying-app-name
JIT_MAPS_ENABLED=true
```

These URLs are administrator configuration, never accepted from a browser request. Use `JIT_MAPS_ENABLED=false` to disable external map lookups. Before distributing a public app, arrange appropriate hosting/capacity and follow the providers' terms; Valhalla's demo maintainers request identification and notification for published apps.

Provider documentation:
- https://github.com/komoot/photon (demo terms)
- https://github.com/komoot/photon/blob/master/docs/api-v1.md (search API)
- https://valhalla.github.io/valhalla/ (demo terms)
- https://valhalla.github.io/valhalla/api/route/api-reference/ (pedestrian routing)

## Backup buses and service advisories

Each primary option includes the next later usable 93 trip serving the **same selected destination stop**, where available. Fresh cancellations and skipped boarding/alighting stops are excluded. Its destination time includes your final walk and can be later than your deadline. Any lateness is rounded up to minutes; it is never rounded into an on-time result. Backups are searched within the service days already loaded for that plan, not an unlimited next-day search. They are contingency estimates, not a claim that you will catch them after an unknown disruption.

Service alerts use `http://gtfs.viainfo.net/alert/alerts.pb` (override with `VIA_ALERTS_URL`). They are fetched at most once per minute. A feed older than ten minutes, missing its timestamp, more than 30 seconds in the future, or affected by a download failure is **unconfirmed**, not an all-clear. Last downloaded notices may remain visible with that warning.

Advisories match active periods and route, agency, route type, direction, trip/service date, and stops along the displayed legs, including backups. Fields inside one GTFS selector are combined with AND, and separate selectors with OR. Before a plan exists, the section shows matching broad route/selected-stop notices for the chosen date; trip-specific filtering is available after planning. Alert text is escaped and only HTTP(S) notice links are displayed.

Alerts are advisory: a closure notice may invalidate an otherwise timed connection. The app warns you to check it and does not automatically reroute or infer cancellation from free text. Unsupported differential feeds are rejected. Vehicle positions and GPS inference remain outside this update. The footer includes **Data provided by VIA Metropolitan Transit** and OpenStreetMap attribution.

## Proving the predictions are useful

1. Open the app during the morning commute and expand **Data status**. Confirm a recent feed timestamp.
2. Plan the trip and check which individual events say **Live estimate**, **boarding proxy**, or **Scheduled**. A connected feed alone does not prove coverage for your buses.
3. Record the route, stop, prediction observation time, predicted arrival, and actual observed arrival/departure. Take observations about 5, 10, and 15 minutes before the bus reaches the stop.
4. Repeat across several commute days. Compare prediction error and missing/stale coverage separately for the 24 and 93. Record whether the actual transfer succeeded and the actual walking time.
5. Adjust your heuristic buffers from that evidence before considering calibrated risk percentages. This release does not collect history or train a model automatically.

The automated tests check parsing and decision logic. They do **not** establish real-world prediction accuracy. A real feed snapshot can demonstrate readable data, but repeated observed trips are needed to assess reliability.

There is also an optional integration test for captured VIA data. Save `google_transit.zip` and a valid daytime `tripupdates.pb` in one folder and run:

```sh
sh mvnw test -Dvia.integration.dir=/absolute/path/to/your/feed-folder
```

It checks parsing and prints how many boarding events match live data at the snapshot's own timestamp. Without that option, tests use small offline fixtures and skip the captured-feed test.

## Saved data and local use

The app binds to `127.0.0.1` and has no user accounts. It is intended for one person on one computer. Keep that setting for this version. Authentication and per-user ownership are needed before making it available to others.

Clicking **Save commute on this computer** stores one commute in `data/commutes.mv.db`. Clicking **Find my departure** does not persist the entered addresses. **Delete saved commute** deletes the saved record; it does not securely erase database history or copies in backups. `data/` is excluded from Git and the project distribution. Addresses are not sent to VIA. Map lookup has the explicit sharing behavior described above. Forms and map lookup endpoints use POST and CSRF protection. Existing saved commutes default to manual mode and Houston & Mittman if they predate the new stop-selection fields.

No passwords or API keys are required for the default configuration. The app does not send leave-home notifications while closed; automatic replanning runs only while the page is open and enabled.

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
| `CorridorStops.java` | Direction/calendar-aware eligible and nearby stops |
| `MapService.java`, `MapController.java` | Explicit address search, pedestrian routes, caching and request validation |
| `AlertStore.java` | Service-alert parsing, relevance, freshness and safe display |
| `ScheduleStore.java` | Static GTFS download, cache, CSV parsing and calendars |
| `RealtimeStore.java` | Protobuf parsing, freshness checks and event matching |
| `CommutePlanner.java` | Route matching, walks, buffers and ranking |
| `TransitModels.java` | Data records and time handling |
| `SavedCommute.java`, `CommuteRepository.java` | Local persistence |
| `SecurityConfig.java` | CSRF protection for the local app |
| `templates/home.html`, `static/app.css`, `static/app.js` | Interface and optional refresh |

Built with Spring Boot 4.1.1, Java 17, Thymeleaf, Spring Data JPA, H2, GTFS Realtime bindings, and Apache Commons CSV.

## This update's validation

Offline tests cover address/stop changes, wrong-direction and inactive stops, map-response parsing, missing walking estimates, backup lateness, canceled backups, alert relevance/expiry/direction/service date, escaped notice content, map endpoint validation/CSRF, and the rendered backup and advisory interface. Captured VIA integration remains opt-in and is not a real-world accuracy test.

## Updating your existing project

Unzip the updated project into a separate folder first. Copy its project files into your original `just-in-transit` folder while keeping your existing `data/` and `.git/` directories. The delivery ZIP excludes local database contents, downloaded feed caches, and build output. Back up your existing data before running an updated development schema.

Validation for the October 12 update: 45 Java tests passed; the optional captured-VIA integration test was skipped because no capture was supplied. `mvn package` succeeded. DOM interaction checks covered location selection, walking estimates, edited-address invalidation, late responses after manual overrides, and failed lookups. Photon and Valhalla responded to public sample requests. A full browser visual check could not be completed because the browser binary download failed in the build environment.
