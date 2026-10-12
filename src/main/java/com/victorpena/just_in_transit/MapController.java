package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/maps")
public class MapController {
    private final MapService maps;
    private final ScheduleStore schedules;
    public MapController(MapService maps,ScheduleStore schedules) { this.maps=maps;this.schedules=schedules; }
    public record Search(@NotBlank @Size(min=3,max=200) String query) { }
    public record Location(@NotBlank @Pattern(regexp="origin|destination") String kind,
        @NotNull @DecimalMin("29.1") @DecimalMax("29.9") Double lat,
        @NotNull @DecimalMin("-99.1") @DecimalMax("-98.0") Double lon,@NotNull LocalDate date) { }
    public record WalkRequest(@NotBlank @Pattern(regexp="origin|destination|transfer") String kind,
        @NotNull @DecimalMin("29.1") @DecimalMax("29.9") Double lat,
        @NotNull @DecimalMin("-99.1") @DecimalMax("-98.0") Double lon,
        @NotBlank @Size(max=64) String stopId,@NotNull LocalDate date) { }
    @PostMapping("/search") public List<MapService.Place> search(@Valid @RequestBody Search query) throws IOException,InterruptedException {
        return maps.search(query.query());
    }
    @PostMapping("/stops") public List<CorridorStops.Nearby> stops(@Valid @RequestBody Location point) {
        return CorridorStops.nearby(schedule(),point.kind(),point.date(),point.lat(),point.lon());
    }
    @PostMapping("/walk") public MapService.Walk walk(@Valid @RequestBody WalkRequest request) throws IOException,InterruptedException {
        Schedule schedule=schedule();
        if("transfer".equals(request.kind())) {
            Stop a=schedule.stops().get("76779"),b=schedule.stops().get("76773");
            if(a==null||b==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Transfer stops unavailable.");
            return maps.walk(a.lat(),a.lon(),b.lat(),b.lon());
        }
        Stop stop=CorridorStops.eligible(schedule,request.kind(),request.date()).stream()
            .filter(s->s.id().equals(request.stopId())).findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a stop serving the transfer on this date."));
        if(!MapService.inArea(request.lat(),request.lon()) || CorridorStops.meters(request.lat(),request.lon(),stop.lat(),stop.lon())>2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No supported stop within the address search area.");
        return "origin".equals(request.kind())?maps.walk(request.lat(),request.lon(),stop.lat(),stop.lon()):
            maps.walk(stop.lat(),stop.lon(),request.lat(),request.lon());
    }
    private Schedule schedule() {
        Schedule schedule=schedules.get();
        if(schedule==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"VIA schedule unavailable. Try again or use manual settings.");
        return schedule;
    }
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String,String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("message","Check the address, date, and selected location. Lookups support the San Antonio area."));
    }
    @ExceptionHandler(MapService.BusyException.class) public ResponseEntity<Map<String,String>> busy() {
        return ResponseEntity.status(429).body(Map.of("message","Please wait a moment, then try the lookup again."));
    }
    @ExceptionHandler({IOException.class,InterruptedException.class}) public ResponseEntity<Map<String,String>> unavailable(Exception error) {
        if(error instanceof InterruptedException)Thread.currentThread().interrupt();
        return ResponseEntity.status(503).body(Map.of("message","Map lookup unavailable. Try again, or select manual stops and enter walking minutes."));
    }
}
