package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/maps")
public class LiveMapController {
    private final ScheduleStore schedules;private final RealtimeStore realtime;
    private final VehicleStore vehicles;private final JourneySession journeys;private final Clock clock;
    public LiveMapController(ScheduleStore schedules,RealtimeStore realtime,VehicleStore vehicles,JourneySession journeys,Clock clock) {
        this.schedules=schedules;this.realtime=realtime;this.vehicles=vehicles;this.journeys=journeys;this.clock=clock;
    }
    public record Status(Instant now,List<Stop> stops,List<LiveMapData.Bus> buses,List<LiveMapData.Countdown> countdowns,
        String message,String vehicleStatus,String predictionStatus,boolean active,String phase,String revision,Integer walkMinutes,Decision decision,String firstRoute,String startMode) { }
    public record Decision(String leave,String work,String deadline,long transfer,String backup,String confidence,String rating) { }
    @GetMapping("/live") public Status live(@RequestParam(defaultValue="") String token,@RequestParam(defaultValue="0") int option,
        @RequestParam(defaultValue="81799") String originStop,@RequestParam(defaultValue="83463") String destinationStop,@RequestParam(defaultValue="work") String corridor) {
        if(!Set.of("home","work").contains(corridor) || token.length()>100 || option<0 || option>2 || !originStop.matches("[A-Za-z0-9_-]{1,64}") || !destinationStop.matches("[A-Za-z0-9_-]{1,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid map selection");
        Schedule schedule=schedules.get();LiveSnapshot live=realtime.get();var positions=vehicles.get();Instant now=clock.instant();
        var state=journeys.current(now);var active=state==null?null:ActiveJourney.view(state,schedule,live,now);
        var choice=active==null?journeys.mapChoice(token,option,now):null;
        CommuteForm form=state!=null?state.form():choice==null?null:choice.form();
        Option selected=active!=null?active.option():choice==null?null:ActiveJourney.preview(form,choice.option(),schedule,live,now);
        if(form!=null){originStop=form.originStop();destinationStop=form.destinationStop();}
        var route=form==null?Corridor.of(corridor):form.route();
        List<Stop> stops=new ArrayList<>();
        if(schedule!=null)for(String id:new LinkedHashSet<>(List.of(originStop,"76779","76773",destinationStop))) {
            Stop stop=schedule.stops().get(id);if(stop!=null && MapService.inArea(stop.lat(),stop.lon()))stops.add(stop);
        }
        String message=active!=null?active.phaseLabel():choice!=null?"Following departure option "+(option+1)+" for "+form.date()+". Times are estimates.":
            token.isBlank()?"Plan your commute to see departure countdowns. Bus positions show current reports.":"This departure plan expired or changed. Find your departure again.";
        if(active==null && selected!=null && !selected.meetsBuffers())message+=" Connection or arrival buffer at risk; review the departure plan.";
        var buses=LiveMapData.buses(positions,schedule,originStop,destinationStop,selected,now,route).stream().map(b-> {
            boolean highlight=b.selected() && (active==null || (b.route().equals(route.firstRoute())?!active.firstDone() && !active.state().needs24():!active.secondDone() && active.hasConnection()));
            return new LiveMapData.Bus(b.id(),b.tripId(),b.route(),b.headsign(),b.lat(),b.lon(),b.reportedAt(),b.validUntil(),b.fresh(),highlight);
        }).toList();
        return new Status(now,List.copyOf(stops),buses,
            selected==null?List.of():LiveMapData.countdowns(form,selected,active,live,now),message,vehicles.message(),realtime.message(),
            active!=null,active==null?"":state.phase().name(),state==null?"":state.revision(),form==null?null:form.walkToStop(),selected==null?null:new Decision(form.atStop() || form.onboard()?selected.first().board().display():selected.leaveDisplay(),selected.workDisplay(),selected.deadlineLabel(),selected.transferMargin(),selected.backupSummary(),selected.confidenceWarning(),selected.displayRating()),route.firstRoute(),form==null?"home":form.startMode());
    }
}
