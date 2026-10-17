package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="jit.personal.enabled", havingValue="false")
public class OfflineCardController {
    private final JourneySession journeys;private final JourneyRecovery recovery;private final ScheduleStore schedules;private final RealtimeStore realtime;private final Clock clock;
    public OfflineCardController(JourneySession journeys,JourneyRecovery recovery,ScheduleStore schedules,RealtimeStore realtime,Clock clock) {
        this.journeys=journeys;this.recovery=recovery;this.schedules=schedules;this.realtime=realtime;this.clock=clock;
    }
    public record Line(String label,String stop,String estimate,String source) { }
    public record Card(boolean available,String title,String phase,String instruction,Instant checkedAt,Instant expiresAt,List<Line> lines,List<String> notes) { }
    @GetMapping("/journey/offline") public Card card(HttpServletRequest request) {
        Instant now=clock.instant();var state=journeys.current(now);if(state==null)state=recovery.load(request,now);
        if(state==null)return new Card(false,"","","",now,now,List.of(),List.of());
        var schedule=schedules.get();var view=ActiveJourney.view(state,schedule,realtime.get(),now);var f=state.form();var o=view.option();var route=f.route();
        var lines=List.of(new Line("Board "+route.firstRoute(),name(schedule,f.originStop()),o.first().board().display(),o.first().board().source()),
            new Line("Leave "+route.firstRoute(),name(schedule,route.transferOff()),o.first().alight().display(),o.first().alight().source()),
            new Line("Board "+route.secondRoute(),name(schedule,route.transferOn()),o.second().board().display(),o.second().board().source()),
            new Line("Leave "+route.secondRoute(),name(schedule,f.destinationStop()),o.second().alight().display(),o.second().alight().source()));
        var notes=new ArrayList<String>();notes.add(f.transferWalk()+" min transfer walk · "+f.walkToWork()+" min final walk.");
        notes.add(view.arrivalAvailable()?"Last destination estimate: "+o.workDisplay()+" · "+view.deadlineLabel():"Destination estimate unavailable.");
        notes.addAll(view.warnings());
        String instruction=switch(state.phase()) {
            case WALKING_TO_24 -> "Walk to "+name(schedule,f.originStop())+" ("+f.walkToStop()+" min entered walk), then board "+route.firstRoute()+" toward Martin & Leona.";
            case WAITING_FOR_24 -> "Wait at "+name(schedule,f.originStop())+" for "+route.firstRoute()+" toward Martin & Leona.";
            case ON_24 -> "Get off at "+name(schedule,route.transferOff())+". Your next bus is "+route.secondRoute()+" at Martin Opposite Leona.";
            case TRANSFERRING -> "Walk "+f.transferWalk()+" minutes to Martin Opposite Leona for "+route.secondRoute()+".";
            case WAITING_FOR_93 -> "Wait at Martin Opposite Leona for "+route.secondRoute()+" to "+name(schedule,f.destinationStop())+".";
            case ON_93 -> "Get off at "+name(schedule,f.destinationStop())+", then allow "+f.walkToWork()+" minutes for the final walk.";
            case WALKING_TO_DESTINATION -> "Continue the final walk ("+f.walkToWork()+" entered minutes). Reconnect to confirm arrival.";
            case ARRIVED -> "Arrival was confirmed. Reconnect to finish the commute.";
        };
        if(!view.hasConnection())instruction="No usable remaining connection was found when this card was saved. Check VIA before boarding; the times below are the previous selection.";
        return new Card(true,route.title()+" · "+route.routes(),view.phaseLabel(),instruction,now,state.started().plusSeconds(18*3600),lines,List.copyOf(notes));
    }
    private static String name(Schedule schedule,String id) { return schedule!=null && schedule.stops().containsKey(id)?schedule.stops().get(id).name():"Stop "+id; }
}
