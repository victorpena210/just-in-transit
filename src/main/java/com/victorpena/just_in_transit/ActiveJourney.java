package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;
import java.util.*;

/** Journey state with an expiring persistence checkpoint. Refresh changes future estimates, never the selected trip IDs. */
public final class ActiveJourney {
    private ActiveJourney() { }
    public enum Phase {
        WALKING_TO_24("Walking to the 24", "I'm on the 24"),
        WAITING_FOR_24("Waiting for the 24", "I'm on the 24"),
        ON_24("On the 24", "I'm off the 24"),
        TRANSFERRING("Walking to the 93 stop", "I'm at the 93 stop"),
        WAITING_FOR_93("Waiting for the 93", "I'm on the 93"),
        ON_93("On the 93", "I'm off the 93"),
        WALKING_TO_DESTINATION("Walking to your destination", "I've arrived"),
        ARRIVED("You've arrived", "");
        private final String label, nextLabel;
        Phase(String label,String nextLabel) { this.label=label;this.nextLabel=nextLabel; }
        public String label() { return label; }
        public String nextLabel() { return nextLabel; }
    }
    public record Facts(String id,Instant plannedDeparture,Instant plannedArrival,boolean keepHistory,Map<String,Instant> confirmations,Map<String,Instant> predictions,Map<String,String> predictionSources) {
        public Facts { confirmations=confirmations==null?Map.of():Map.copyOf(confirmations);predictions=predictions==null?Map.of():Map.copyOf(predictions);predictionSources=predictionSources==null?Map.of():Map.copyOf(predictionSources); }
        public Facts(String id,Instant plannedDeparture,Instant plannedArrival,boolean keepHistory,Map<String,Instant> confirmations) { this(id,plannedDeparture,plannedArrival,keepHistory,confirmations,Map.of(),Map.of()); }
        Facts confirm(String event,Instant at) {
            var updated=new HashMap<>(confirmations);updated.put(event,at);
            return new Facts(id,plannedDeparture,plannedArrival,keepHistory,updated,predictions,predictionSources);
        }
    }
    public record State(String revision,CommuteForm form,Option option,Phase phase,Instant started,
                        Instant changed,Set<String> missed,boolean needs93,boolean needs24,Facts facts,State previous) {
        public State {
            missed=missed==null?Set.of():Set.copyOf(missed);
            if(facts==null)facts=new Facts(UUID.randomUUID().toString(),null,option.atWork(),false,Map.of());
        }
        public State(String revision,CommuteForm form,Option option,Phase phase,Instant started,Instant changed,Set<String> missed,boolean needs93,boolean needs24) {
            this(revision,form,option,phase,started,changed,missed,needs93,needs24,null,null);
        }
        public State(String revision,CommuteForm form,Option option,Phase phase,Instant started,Instant changed,Set<String> missed,boolean needs93) {
            this(revision,form,option,phase,started,changed,missed,needs93,false);
        }
        public boolean expired(Instant now) { return !now.isBefore(started.plus(Duration.ofHours(18))); }
        public String label() { return routeText(form,phase.label()); }
        State snapshot() { return new State(revision,form,option,phase,started,changed,missed,needs93,needs24,facts,null); }
    }
    private static String routeText(CommuteForm f,String text) {
        return text.replace("24","{first}").replace("93","{second}").replace("{first}",f.route().firstRoute()).replace("{second}",f.route().secondRoute());
    }
    private static State changed(State old,Option option,Phase phase,Instant now,Set<String> missed,boolean needsSecond,boolean needsFirst,String event) {
        return new State(UUID.randomUUID().toString(),old.form(),option,phase,old.started(),now,missed,needsSecond,needsFirst,
            event==null?old.facts():old.facts().confirm(event,now),old.snapshot());
    }
    public static State undo(State state,Instant now) {
        if(state.previous()==null)throw new IllegalArgumentException("There is no previous step to undo.");
        var old=state.previous();
        return new State(UUID.randomUUID().toString(),old.form(),old.option(),old.phase(),old.started(),old.changed(),old.missed(),old.needs93(),old.needs24(),old.facts(),null);
    }
    public record View(State state,Option option,List<String> warnings,String deadlineLabel) {
        public String phaseLabel() { return state.label(); }
        public String nextLabel() { return routeText(state.form(),state.phase().nextLabel()); }
        public boolean canUndo() { return state.previous()!=null; }
        public boolean finished() { return state.phase()==Phase.ARRIVED; }
        public boolean canMiss93() { return state.phase()==Phase.WAITING_FOR_93; }
        public boolean hasConnection() { return !state.needs93() && !state.needs24(); }
        public boolean canMiss24() { return !firstBoardDone(); }
        public boolean canMarkAtStop() { return state.phase()==Phase.WALKING_TO_24; }
        public boolean firstBoardDone() { return state.phase().ordinal()>=Phase.ON_24.ordinal(); }
        public boolean firstDone() { return state.phase().ordinal()>=Phase.TRANSFERRING.ordinal(); }
        public boolean secondBoardDone() { return state.phase().ordinal()>=Phase.ON_93.ordinal(); }
        public boolean secondDone() { return state.phase().ordinal()>=Phase.WALKING_TO_DESTINATION.ordinal(); }
        public boolean arrivalAvailable() {
            return finished() || hasConnection() && option.second().alight().usable()
                && (secondBoardDone() || option.second().board().usable())
                && (firstDone() || option.first().board().usable() && option.first().alight().usable());
        }
        public boolean canAdvance() {
            if(finished() || state.needs93() || state.needs24())return false;
            // Do not invite boarding a trip known to be canceled or to skip this stop.
            if(!firstBoardDone())return option.first().board().usable();
            if(state.phase()==Phase.WAITING_FOR_93)return option.second().board().usable();
            return true;
        }
    }
    public static State start(CommuteForm form,Option selected,Schedule schedule,LiveSnapshot live,Instant now) {
        return start(form,selected,schedule,live,now,false);
    }
    public static State start(CommuteForm form,Option selected,Schedule schedule,LiveSnapshot live,Instant now,boolean keepHistory) {
        if(!form.date().equals(now.atZone(ZONE).toLocalDate()))
            throw new IllegalArgumentException("Start this commute on its travel date.");
        var first=refreshLeg(selected.first(),schedule,live,form.originStop(),form.route().transferOff(),false,false,now);
        var second=refreshLeg(selected.second(),schedule,live,form.route().transferOn(),form.destinationStop(),false,false,now);
        if(!first.board().usable() || !first.alight().usable() || !second.board().usable() || !second.alight().usable())
            throw new IllegalArgumentException("A selected bus is unavailable. Refresh the departure plan before starting.");
        if(!form.onboard() && now.plusSeconds((form.atStop()?0:form.walkToStop())*60L).isAfter(first.board().at()))
            throw new IllegalArgumentException("There is no longer enough time to reach that bus. Select your current stage and find a new departure.");
        if(second.board().at().isBefore(first.alight().at().plusSeconds(form.transferWalk()*60L)))
            throw new IllegalArgumentException("The selected connection no longer leaves enough time to transfer. Find a new departure.");
        if(form.onboard() && (!key(first).equals(form.firstTrip()) || first.board().at().isBefore(now.minusSeconds(7200))
            || first.board().at().isAfter(now.plusSeconds(300)) || !first.alight().at().isAfter(now)))
            throw new IllegalArgumentException("Choose the bus you actually boarded from the current trip list.");
        var option=option(form,first,second,selected.leaveHome(),first.alight().at().plusSeconds(form.transferWalk()*60L));
        Phase phase=form.onboard()?Phase.ON_24:form.atStop()?Phase.WAITING_FOR_24:Phase.WALKING_TO_24;
        var facts=new Facts(UUID.randomUUID().toString(),phase==Phase.WALKING_TO_24?selected.leaveHome():null,option.atWork(),keepHistory,
            Map.of(phase==Phase.WALKING_TO_24?"leftOrigin":phase==Phase.WAITING_FOR_24?"atFirstStop":"joinedOnboard",now),
            Map.of("ON_24",first.board().at(),"TRANSFERRING",first.alight().at(),"ON_93",second.board().at(),"WALKING_TO_DESTINATION",second.alight().at(),"ARRIVED",option.atWork()),
            Map.of("ON_24",first.board().source(),"TRANSFERRING",first.alight().source(),"ON_93",second.board().source(),"WALKING_TO_DESTINATION",second.alight().source(),"ARRIVED","Bus estimate plus entered walk"));
        return new State(UUID.randomUUID().toString(),form,option,phase,now,now,Set.of(),false,false,facts,null);
    }
    public static View view(State state,Schedule schedule,LiveSnapshot live,Instant now) {
        var f=state.form();var phase=state.phase();
        boolean on24=phase.ordinal()>=Phase.ON_24.ordinal(),off24=phase.ordinal()>=Phase.TRANSFERRING.ordinal();
        boolean on93=phase.ordinal()>=Phase.ON_93.ordinal(),off93=phase.ordinal()>=Phase.WALKING_TO_DESTINATION.ordinal();
        Leg first=refreshLeg(state.option().first(),schedule,live,f.originStop(),f.route().transferOff(),on24,off24,now);
        Leg second=refreshLeg(state.option().second(),schedule,live,f.route().transferOn(),f.destinationStop(),on93,off93,now);
        Instant ready=off24?phase==Phase.TRANSFERRING?later(now,state.changed().plusSeconds(f.transferWalk()*60L)):now
            :later(now,first.alight().at()).plusSeconds(f.transferWalk()*60L);
        Option updated=option(f,first,second,state.option().leaveHome(),ready);
        List<String> warnings=new ArrayList<>();
        if(!on24 && (!first.board().usable() || !first.alight().usable()))warnings.add("Your selected 24 is unavailable or a required stop is skipped. Check VIA before continuing; your journey has been kept.");
        if(on24 && !off24 && !first.alight().usable())warnings.add("The 24's arrival at your transfer stop is unconfirmed. Check with the driver.");
        if(!on24 && now.isAfter(first.board().at()))warnings.add("The 24's boarding estimate has passed. If you boarded, tap “I'm on the 24”; otherwise use “I missed the 24” once you reach the boarding stop.");
        if(!off24 && now.isAfter(first.alight().at()))warnings.add("The 24's transfer estimate has passed. Confirm when you get off; the remaining connection is unconfirmed.");
        if(!on93 && !state.needs93() && !state.needs24()) {
            if(!second.board().usable() || !second.alight().usable())warnings.add("Your selected 93 is unavailable or a required stop is skipped. Once at the transfer stop, use “Find another 93”.");
            else if(second.board().at().isBefore(ready))warnings.add("The selected 93 is earlier than your estimated readiness at the transfer stop. Your buses stay selected until you choose another 93.");
            else if(updated.transferMargin()<f.transferBuffer())warnings.add("The remaining transfer is tighter than your requested buffer.");
            if(updated.transferMargin()>f.maxTransferWait())warnings.add("The remaining transfer wait exceeds your preferred maximum. Check another departure if needed.");
            if(!first.alight().directPrediction() && !off24 || !second.board().directPrediction())warnings.add("Connection unconfirmed: some remaining transfer times are scheduled, inferred, or uncertain. Check the event labels.");
        }
        if(state.needs24())warnings.add("No usable replacement 24 → 93 connection was found within six hours. Your boarding stop and deadline are kept. Check again or consult VIA for another route.");
        if(state.needs93())warnings.add("No later usable 93 was found within six hours. Your transfer progress is saved. Check again or consult VIA for another route.");
        if(on93 && !off93 && !second.alight().usable())warnings.add("Arrival at your selected stop is unconfirmed. Check with the driver.");
        if(!off93 && !state.needs93() && !state.needs24() && !second.alight().directPrediction())warnings.add("Destination arrival includes a scheduled or inferred bus estimate; it is not a confirmed arrival time.");
        if(!off93 && now.isAfter(second.alight().at()) && !state.needs93())warnings.add("The 93's arrival estimate has passed. Confirm your progress; this remaining estimate may be out of date.");
        if(phase==Phase.WALKING_TO_DESTINATION && now.isAfter(updated.atWork()))warnings.add("Your walking estimate has passed. Mark arrived when you reach your destination.");
        if(phase!=Phase.ARRIVED && !f.departing() && CommuteTiming.arrivalSeconds(f,updated)>f.maxEarlyArrival()*60L)
            warnings.add("Your current arrival estimate is earlier than your preferred arrival window. Your selected buses are unchanged.");
        Instant atWork=phase==Phase.ARRIVED?state.changed():updated.atWork();
        long seconds=Duration.between(atWork,f.date().atTime(f.arriveBy()).atZone(ZONE).toInstant()).getSeconds();
        String deadline=seconds<0?(long)Math.ceil(-seconds/60.0)+" min late":seconds<60?"At your deadline":seconds/60+" min before your deadline";
        if(phase==Phase.ARRIVED)updated=new Option(first,second,updated.leaveHome(),atWork,0,seconds/60,0,seconds>=0,"Arrived",null,null,!f.departing());
        return new View(state,updated,warnings.stream().map(w->routeText(f,w)).toList(),f.departing()?"No arrival deadline set":deadline);
    }
    public static State advance(State state,Schedule schedule,LiveSnapshot live,Instant now) {
        View view=view(state,schedule,live,now);
        if(!view.canAdvance())throw new IllegalArgumentException("That progress update is not available. Check your current journey.");
        var next=state.phase()==Phase.WALKING_TO_24?Phase.ON_24:Phase.values()[state.phase().ordinal()+1];var o=view.option();var first=o.first();var second=o.second();
        Event confirmed=new Event(now,"You confirmed · "+time(now),false,true);
        if(next==Phase.ON_24)first=new Leg(first.route(),first.tripId(),confirmed,first.alight(),first.serviceDate());
        if(next==Phase.TRANSFERRING)first=new Leg(first.route(),first.tripId(),first.board(),confirmed,first.serviceDate());
        if(next==Phase.ON_93)second=new Leg(second.route(),second.tripId(),confirmed,second.alight(),second.serviceDate());
        if(next==Phase.WALKING_TO_DESTINATION)second=new Leg(second.route(),second.tripId(),second.board(),confirmed,second.serviceDate());
        var captured=option(state.form(),first,second,o.leaveHome(),first.alight().at().plusSeconds(state.form().transferWalk()*60L));
        return changed(state,captured,next,now,state.missed(),false,false,next.name());
    }
    public static State at24(State state,Instant now) {
        if(state.phase()!=Phase.WALKING_TO_24)throw new IllegalArgumentException("Your current step is no longer the walk to the first bus.");
        return changed(state,state.option(),Phase.WAITING_FOR_24,now,state.missed(),false,false,"atFirstStop");
    }
    public static State missed24(State state,Schedule schedule,LiveSnapshot live,Instant now) {
        if(state.phase()!=Phase.WAITING_FOR_24)throw new IllegalArgumentException("Confirm that you are at the first boarding stop first.");
        var missed=new HashSet<>(state.missed());
        if(!state.needs24())missed.add(key(state.option().first()));
        Option next=CommutePlanner.recover24(state.form(),schedule,live,now,missed);
        return changed(state,next==null?state.option():next,Phase.WAITING_FOR_24,now,Set.copyOf(missed),false,next==null,"missedFirst");
    }
    public static State missed93(State state,Schedule schedule,LiveSnapshot live,Instant now) {
        if(state.phase()!=Phase.WAITING_FOR_93)throw new IllegalArgumentException("Confirm that you are at the second boarding stop first.");
        var missed=new HashSet<>(state.missed());missed.add(key(state.option().second()));
        List<Leg> candidates=new ArrayList<>();
        LocalDate today=now.atZone(ZONE).toLocalDate();
        if(schedule!=null) {
            candidates.addAll(CommutePlanner.legs(schedule,live,state.form().route().secondRoute(),state.form().route().transferOn(),state.form().destinationStop(),today,now));
            candidates.addAll(CommutePlanner.legs(schedule,live,state.form().route().secondRoute(),state.form().route().transferOn(),state.form().destinationStop(),today.plusDays(1),now));
        }
        Leg next=candidates.stream().filter(l->!missed.contains(key(l)) && !l.board().at().isBefore(now)
            && !l.board().at().isAfter(now.plusSeconds(6*3600))).min(Comparator.comparing((Leg l)->l.alight().at()).thenComparing(l->l.board().at())).orElse(null);
        var old=view(state,schedule,live,now).option();
        Option selected=next==null?old:option(state.form(),old.first(),next,old.leaveHome(),now);
        return changed(state,selected,state.phase(),now,Set.copyOf(missed),next==null,false,"missedTransfer");
    }
    public static State replaceSecond(State state,Schedule schedule,LiveSnapshot live,Instant now) {
        var v=view(state,schedule,live,now);
        if(!v.firstBoardDone() || v.secondBoardDone())throw new IllegalArgumentException("A replacement transfer can be chosen while on the first bus or at the transfer.");
        var next=JourneyGuidance.alternative(v,schedule,live,now);
        if(next==null)throw new IllegalArgumentException("No usable alternative remains. Your current trip is kept; check again.");
        return new State(UUID.randomUUID().toString(),state.form(),next,state.phase(),state.started(),state.changed(),state.missed(),false,false,state.facts(),state.snapshot());
    }
    private static String key(Leg leg) { return leg.tripId()+"@"+leg.serviceDate(); }
    /** Refresh the same offered buses without replanning or starting a journey. */
    public static Option preview(CommuteForm f,Option old,Schedule schedule,LiveSnapshot live,Instant now) {
        Leg first=refreshLeg(old.first(),schedule,live,f.originStop(),f.route().transferOff(),false,false,now);
        Leg second=refreshLeg(old.second(),schedule,live,f.route().transferOn(),f.destinationStop(),false,false,now);
        var current=option(f,first,second,f.atStop() || f.onboard()?now:first.board().at().minusSeconds((f.walkToStop()+f.boardingBuffer())*60L),
            first.alight().at().plusSeconds(f.transferWalk()*60L));
        Backup backup=null;
        if(old.backup()!=null) {
            var leg=refreshLeg(old.backup().leg(),schedule,live,f.route().transferOn(),f.destinationStop(),false,false,now);
            if(leg.board().usable() && leg.alight().usable() && leg.board().at().isAfter(second.board().at())) {
                var work=leg.alight().at().plusSeconds(f.walkToWork()*60L);
                backup=new Backup(leg,work,Duration.between(second.board().at(),leg.board().at()).toMinutes(),
                    Duration.between(work,f.date().atTime(f.arriveBy()).atZone(ZONE).toInstant()).getSeconds(),!f.departing());
            }
        }
        long firstWait=f.atStop()?Duration.between(now,first.board().at()).getSeconds():f.boardingBuffer()*60L;
        boolean firstFits=f.onboard() || firstWait>=f.boardingBuffer()*60L && firstWait<=f.maxBoardingWait()*60L;
        String rating=!firstFits?(firstWait<f.boardingBuffer()*60L?"Tight first boarding":"Longer wait than preferred"):current.rating();
        if(!f.departing() && current.arrivalMargin()<0)rating="Late arrival";
        return new Option(first,second,current.leaveHome(),current.atWork(),current.transferMargin(),current.arrivalMargin(),
            current.transferWait(),current.meetsBuffers() && firstFits,rating,backup==null?null:backup.minutesLater(),backup,!f.departing());
    }
    private static Instant later(Instant a,Instant b) { return a.isAfter(b)?a:b; }
    private static Leg refreshLeg(Leg old,Schedule schedule,LiveSnapshot live,String from,String to,
                                  boolean boarded,boolean alighted,Instant now) {
        if(alighted)return old;
        Trip trip=schedule==null?null:schedule.trips().stream().filter(t->t.id().equals(old.tripId())
            && t.route().equals(old.route()) && schedule.active(t.service(),old.serviceDate())).findFirst().orElse(null);
        StopTime start=null,end=null;
        if(trip!=null)for(var stop:trip.stops()) {
            if(start==null && stop.id().equals(from) && stop.pickup())start=stop;
            else if(start!=null && stop.id().equals(to) && stop.dropoff()) { end=stop;break; }
        }
        if(start==null || end==null)return new Leg(old.route(),old.tripId(),boarded?old.board():unconfirmed(old.board()),unconfirmed(old.alight()),old.serviceDate());
        Event board=boarded?old.board():live.event(trip,old.serviceDate(),start,true,now);
        Event alight=live.event(trip,old.serviceDate(),end,false,now);
        if(alight.at().isBefore(board.at()))alight=unconfirmed(alight);
        return new Leg(old.route(),old.tripId(),board,alight,old.serviceDate());
    }
    private static Event unconfirmed(Event old) { return new Event(old.at(),"Last estimate · unconfirmed",false,false); }
    static Option option(CommuteForm f,Leg first,Leg second,Instant leave,Instant ready) {
        Instant work=second.alight().at().plusSeconds(f.walkToWork()*60L);
        long transfer=Duration.between(ready,second.board().at()).getSeconds();
        long arrival=Duration.between(work,f.date().atTime(f.arriveBy()).atZone(ZONE).toInstant()).getSeconds();
        boolean meets=transfer>=(f.transferBuffer()+f.trafficAllowance())*60L && transfer<=f.maxTransferWait()*60L && CommuteTiming.arrivalFits(f,arrival);
        return new Option(first,second,leave,work,Math.floorDiv(transfer,60),Math.floorDiv(arrival,60),Math.floorDiv(transfer,60),meets,
            meets?"Buffers met":!f.departing() && arrival<0?"Late arrival":transfer<0?"Connection at risk":!f.departing() && arrival>f.maxEarlyArrival()*60L?"Earlier than your limit":"Tight timing",null,null,!f.departing());
    }
}
