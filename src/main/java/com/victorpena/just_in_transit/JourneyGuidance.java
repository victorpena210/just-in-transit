package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;
import java.util.*;

/** A read-only next-step and backup assessment. Never silently changes the selected bus. */
public record JourneyGuidance(String status,String tone,String nextStep,String change,String backup,boolean canReplace) {
    public static JourneyGuidance from(ActiveJourney.View v,Schedule schedule,LiveSnapshot live,Instant now) {
        var state=v.state();var f=state.form();var o=v.option();
        if(v.finished())return new JourneyGuidance("Arrived","comfortable","Your opted-in report is saved. Finish when you are ready.","","",false);
        if(state.ride()!=null && (state.phase()==ActiveJourney.Phase.ON_24 || state.phase()==ActiveJourney.Phase.ON_93)) {
            var leg=state.phase()==ActiveJourney.Phase.ON_24?o.first():o.second();
            String target=state.phase()==ActiveJourney.Phase.ON_24?"Martin & Leona":"your selected destination stop";
            return new JourneyGuidance(leg.alight().usable()?"Following your bus":"On board · arrival unconfirmed",leg.alight().usable()?"comfortable":"uncertain",
                leg.alight().usable()?"Your bus is estimated at "+target+" at "+leg.alight().display()+". Confirm when you get off.":"Your bus selection is saved. Check the destination with the driver; we are waiting for a fresh matching onward trip.",
                "You confirmed vehicle "+state.ride().vehicleId()+". Earlier boarding times are not inferred from your location.","",false);
        }
        boolean passed=!v.firstBoardDone() && !o.first().board().at().isAfter(now)
            || !v.firstDone() && !o.first().alight().at().isAfter(now)
            || !v.secondBoardDone() && !o.second().board().at().isAfter(now)
            || !v.secondDone() && !o.second().alight().at().isAfter(now)
            || v.secondDone() && !o.atWork().isAfter(now);
        long firstAllowance=v.firstDone()?0:f.trafficAllowance();
        long secondAllowance=v.secondDone()?0:f.trafficAllowance();
        Instant firstReady=state.started().plusSeconds(f.walkToStop()*60L);
        if(!v.canMarkAtStop() || firstReady.isBefore(now))firstReady=now;
        long firstWait=Duration.between(firstReady,o.first().board().at()).toMinutes();
        boolean tight=!v.firstBoardDone() && (firstWait<f.boardingBuffer() || firstWait>f.maxBoardingWait())
            || !v.secondBoardDone() && o.transferMargin()<f.transferBuffer()+firstAllowance
            || !f.departing() && o.arrivalMargin()<f.arrivalBuffer()+secondAllowance;
        boolean direct=(v.firstBoardDone() || o.first().board().directPrediction())
            && (v.firstDone() || o.first().alight().directPrediction())
            && (v.secondBoardDone() || o.second().board().directPrediction())
            && (v.secondDone() || o.second().alight().directPrediction());
        String status=!v.arrivalAvailable()?"Plan needs attention":passed?"Confirm your progress":
            !f.departing() && o.arrivalMargin()<0?"Running late":tight?"Timing at risk":!f.departing() && o.arrivalMargin()>f.maxEarlyArrival()?"Earlier than your arrival window":direct?"On track":"On schedule · unconfirmed";
        String tone=!v.arrivalAvailable() || tight || passed?"tight":direct?"comfortable":"uncertain";
        String next=switch(state.phase()) {
            case WALKING_TO_24 -> "Walk to your first stop ("+f.walkToStop()+" min). Board the "+f.route().firstRoute()+" at "+o.first().board().display()+". Confirm when you reach the stop.";
            case WAITING_FOR_24 -> "Wait at your selected stop for the "+f.route().firstRoute()+" at "+o.first().board().display()+". Confirm boarding, or tap the missed-bus button if it has gone.";
            case ON_24 -> "Stay on the "+f.route().firstRoute()+" to Martin & Leona, estimated "+o.first().alight().display()+". Then walk "+f.transferWalk()+" min to Martin Opposite Leona.";
            case TRANSFERRING -> "Finish the "+f.transferWalk()+" min transfer walk to Martin Opposite Leona. Confirm you are at the stop before boarding.";
            case WAITING_FOR_93 -> "Board the "+f.route().secondRoute()+" at "+o.second().board().display()+". "+Math.max(0,o.transferMargin())+" min estimated wait from now.";
            case ON_93 -> "Stay on the "+f.route().secondRoute()+" to your selected stop, estimated "+o.second().alight().display()+". Then walk "+f.walkToWork()+" min to your destination.";
            case WALKING_TO_DESTINATION -> "Walk "+f.walkToWork()+" min to your destination. Confirm arrival when you get there.";
            case ARRIVED -> "You've arrived.";
        };
        if(!v.hasConnection())next="Your last confirmed step is saved. Use the replacement-bus button to check again; your deadline stays unchanged.";
        long shift=state.facts().plannedArrival()==null?0:Duration.between(state.facts().plannedArrival(),o.atWork()).toMinutes();
        String change=shift==0?"Arrival estimate unchanged from the start of this trip.":Math.abs(shift)+" min "+(shift>0?"later":"earlier")+" than when you started.";
        Option alternative=alternative(v,schedule,live,now);
        String backup=alternative==null?(v.secondBoardDone()?"":"No usable alternative found within six hours. Check VIA if this connection no longer works."):
            "Alternative: "+(!v.firstBoardDone()?f.route().firstRoute()+" at "+alternative.first().board().display()+" → ":"")
                +f.route().secondRoute()+" at "+alternative.second().board().display()+"; destination "+alternative.workDisplay()+" · "+alternative.deadlineLabel()+". "+alternative.dataLabel()+".";
        return new JourneyGuidance(status,tone,next,change,backup,alternative!=null && v.firstBoardDone() && !v.secondBoardDone());
    }
    static Instant ready(ActiveJourney.View v,Instant now) {
        if(v.state().phase()==ActiveJourney.Phase.WAITING_FOR_93)return now;
        Instant arrival=v.firstDone()?v.state().changed():v.option().first().alight().at();
        Instant ready=arrival.plusSeconds(v.state().form().transferWalk()*60L);
        return ready.isAfter(now)?ready:now;
    }
    static Option alternative(ActiveJourney.View v,Schedule schedule,LiveSnapshot live,Instant now) {
        if(schedule==null || v.secondBoardDone())return null;
        var f=v.state().form();var o=v.option();
        if(!v.firstBoardDone()) {
            // Before reaching the first stop, preserve the remaining initial walk.
            var remaining=f.withTime(now.atZone(ZONE).toLocalDate(),f.arriveBy(),f.planMode(),v.canMarkAtStop()?"home":"stop","");
            var choices=new CommutePlanner().plan(remaining,schedule,live,now).options();
            return choices.stream().filter(c->!CommutePlanner.legKey(c.first()).equals(CommutePlanner.legKey(o.first()))
                && !v.state().missed().contains(CommutePlanner.legKey(c.first()))).findFirst().orElse(null);
        }
        if(!v.firstDone() && (!o.first().alight().usable() || o.first().alight().at().isBefore(now)))return null;
        Instant ready=ready(v,now);
        var days=List.of(now.atZone(ZONE).toLocalDate(),now.atZone(ZONE).toLocalDate().plusDays(1));
        var options=days.stream().flatMap(day->CommutePlanner.legs(schedule,live,f.route().secondRoute(),f.route().transferOn(),f.destinationStop(),day,now).stream())
            .filter(l->!CommutePlanner.legKey(l).equals(CommutePlanner.legKey(o.second()))
                && !v.state().missed().contains(CommutePlanner.legKey(l)) && !l.board().at().isBefore(ready.plusSeconds((f.transferBuffer()+(v.firstDone()?0:f.trafficAllowance()))*60L))
                && !l.board().at().isAfter(now.plusSeconds(6*3600)))
            .map(l->ActiveJourney.option(f,o.first(),l,o.leaveHome(),ready)).toList();
        return options.stream().min(Comparator.comparing((Option c)->c.hasDeadline() && c.arrivalMargin()<0)
            .thenComparing(Comparator.comparing(Option::meetsBuffers).reversed()).thenComparing(Option::atWork)).orElse(null);
    }
}
