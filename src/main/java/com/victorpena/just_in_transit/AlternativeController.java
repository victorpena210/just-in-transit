package com.victorpena.just_in_transit;
import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;import java.util.*;
import org.springframework.stereotype.Controller;import org.springframework.ui.Model;import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import org.springframework.validation.BindingResult;import org.springframework.format.annotation.DateTimeFormat;
@Controller
public class AlternativeController {
    private final ScheduleStore schedules;private final RealtimeStore realtime;private final AlternativePlanner planner;private final BrowserCommuteStore saved;private final Clock clock;
    public AlternativeController(ScheduleStore schedules,RealtimeStore realtime,AlternativePlanner planner,BrowserCommuteStore saved,Clock clock) { this.schedules=schedules;this.realtime=realtime;this.planner=planner;this.saved=saved;this.clock=clock; }
    public record Search(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String origin,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String destination,
        @NotNull @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,@NotNull @DateTimeFormat(iso=DateTimeFormat.ISO.TIME) LocalTime time,
        @NotNull @Min(0) @Max(120) Integer firstWalk,@NotNull @Min(0) @Max(120) Integer lastWalk,
        @NotNull @Min(1) @Max(30) Integer transferWalk,@NotNull @Min(1) @Max(30) Integer boardingBuffer,
        @NotNull @Min(1) @Max(30) Integer transferBuffer,@NotNull @Min(1) @Max(90) Integer maxWait) { }
    @GetMapping("/alternatives") public String page(@RequestParam(defaultValue="work") String corridor,Model model) {
        var now=clock.instant().atZone(ZONE).plusMinutes(1);var f=saved.findById(Corridor.of(corridor).savedId()).map(s->s.form(now.toLocalDate())).orElse(null);
        model.addAttribute("search",new Search(f==null?Corridor.of(corridor).defaultOrigin():f.originStop(),f==null?Corridor.of(corridor).defaultDestination():f.destinationStop(),now.toLocalDate(),now.toLocalTime().withSecond(0).withNano(0),
            f==null?0:f.walkToStop(),f==null?0:f.walkToWork(),f==null?3:f.transferWalk(),f==null?3:f.boardingBuffer(),f==null?5:f.transferBuffer(),f==null?20:f.maxTransferWait()));return "alternatives";
    }
    @PostMapping("/alternatives") public String search(@Valid @ModelAttribute("search") Search f,BindingResult errors,Model model) {
        if(errors.hasErrors())return "alternatives";
        Instant from=f.date().atTime(f.time()).atZone(ZONE).toInstant();var schedule=schedules.get();
        if(from.isBefore(clock.instant().minusSeconds(60)) || from.isAfter(clock.instant().plusSeconds(180L*86400)))errors.rejectValue("date","range","Choose a future departure within 180 days.");
        if(schedule==null)errors.reject("schedule","VIA's schedule is unavailable. Try again shortly.");
        else if(!schedule.stops().containsKey(f.origin()) || !schedule.stops().containsKey(f.destination()) || f.origin().equals(f.destination()))errors.reject("stops","Choose two different valid stop IDs.");
        if(errors.hasErrors())return "alternatives";
        var live=realtime.get();model.addAttribute("choices",planner.search(schedule,live,f.origin(),f.destination(),from,f.firstWalk(),f.lastWalk(),f.transferWalk(),f.boardingBuffer(),f.transferBuffer(),f.maxWait(),clock.instant()));
        model.addAttribute("feedUpdated",live.updatedDisplay());model.addAttribute("scheduleStatus",schedules.message());return "alternatives";
    }
    @GetMapping("/alternatives/stops") @ResponseBody public List<Stop> stops(@RequestParam(defaultValue="") String q) {
        if(q.length()<2 || q.length()>100)return List.of();var s=schedules.get();if(s==null)return List.of();String text=q.toLowerCase(Locale.ROOT);
        return s.stops().values().stream().filter(x->x.id().contains(text) || x.name().toLowerCase(Locale.ROOT).contains(text)).sorted(Comparator.comparing(Stop::name)).limit(20).toList();
    }
}
