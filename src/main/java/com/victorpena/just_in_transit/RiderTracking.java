package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import static com.victorpena.just_in_transit.ActiveJourney.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;

/** A rider-confirmed physical bus. GPS suggests; only a rider action changes their stage. */
@Service
public class RiderTracking {
    private final VehicleStore vehicles;private final ScheduleStore schedules;private final RealtimeStore realtime;
    public RiderTracking(VehicleStore vehicles,ScheduleStore schedules,RealtimeStore realtime) { this.vehicles=vehicles;this.schedules=schedules;this.realtime=realtime; }
    public State observe(State state,Instant now) { return observe(state,schedules.get(),realtime.get(),vehicles.get(),now); }
    static boolean fresh(VehicleStore.Snapshot feed,VehicleStore.Position p,Instant now) {
        return p.reportedAt()!=null && recent(feed.timestamp(),now) && recent(p.reportedAt(),now);
    }
    private static boolean recent(Instant at,Instant now) { long age=Duration.between(at,now).getSeconds();return age>=-30 && age<=90; }
    static LocalDate day(VehicleStore.Position p) {
        try { return LocalDate.parse(p.serviceDate(),DateTimeFormatter.BASIC_ISO_DATE); } catch(RuntimeException e) { return null; }
    }
    static State observe(State state,Schedule schedule,LiveSnapshot live,VehicleStore.Snapshot feed,Instant now) {
        if(state==null || state.ride()==null || state.phase()!=Phase.ON_24 && state.phase()!=Phase.ON_93)return state;
        var ride=state.ride();var f=state.form();boolean first=state.phase()==Phase.ON_24;
        Leg old=first?state.option().first():state.option().second();
        Event arrival=new Event(old.alight().at(),"Bus location or onward trip unconfirmed",false,false);
        String tripId=old.tripId();LocalDate serviceDate=old.serviceDate();
        var position=feed.positions().stream().filter(p->p.id().equals(ride.vehicleId()) && fresh(feed,p,now)).findFirst().orElse(null);
        Trip trip=position==null || schedule==null?null:schedule.trips().stream().filter(t->t.id().equals(position.tripId()) && t.route().equals(ride.route())
            && (position.route().isBlank() || position.route().equals(t.route()))).findFirst().orElse(null);
        LocalDate date=position==null?null:day(position);
        if(trip!=null && date!=null && schedule.active(trip.service(),date)) {
            tripId=trip.id();serviceDate=date;
            String target=first?f.route().transferOff():f.destinationStop();
            var stops=trip.stops().stream().filter(s->s.id().equals(target) && s.dropoff()).toList();
            arrival=stops.stream().map(s->live.event(trip,date,s,false,now)).filter(e->e.usable() && !e.at().isBefore(now.minusSeconds(60)))
                .min(Comparator.comparing(Event::at)).orElse(new Event(now,"This trip has no upcoming arrival at your stop",false,false));
        }
        Leg leg=new Leg(ride.route(),tripId,old.board(),arrival,serviceDate);
        Option o=option(f,first?leg:state.option().first(),first?state.option().second():leg,state.option().leaveHome(),now);
        return new State(state.revision(),f,o,state.phase(),state.started(),state.changed(),state.missed(),state.needs93(),state.needs24(),state.facts(),state.previous(),ride);
    }
    public State select(State old,CommuteForm form,String route,String vehicleId,boolean driverConfirmed,boolean keepHistory,Instant at,Instant now) {
        if(route==null || !Set.of(form.route().firstRoute(),form.route().secondRoute()).contains(route))throw new IllegalArgumentException("Choose a 24 or 93 for this commute.");
        if(vehicleId==null || !vehicleId.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Enter the vehicle number shown on your bus, or select a bus on the map.");
        if(old!=null && old.phase()==Phase.ARRIVED)throw new IllegalArgumentException("Finish your arrived trip before selecting another bus.");
        boolean first=route.equals(form.route().firstRoute());
        if(old!=null && first && old.phase().ordinal()>Phase.ON_24.ordinal())throw new IllegalArgumentException("You are past the first bus. End this trip before starting a different journey.");
        var unknown=new Event(at,"Earlier journey not recorded",false,false);
        LocalDate date=now.atZone(ZONE).toLocalDate();
        var prior=old==null?null:old.option();
        var firstLeg=prior==null?new Leg(form.route().firstRoute(),"",unknown,unknown,date):prior.first();
        var secondLeg=prior==null?new Leg(form.route().secondRoute(),"",unknown,unknown,date):prior.second();
        var board=new Event(at,"On board confirmed · boarding time not recorded",false,true);
        var selected=new Leg(route,"",board,new Event(at,"Arrival unconfirmed",false,false),date);
        if(first)firstLeg=selected;else secondLeg=selected;
        Facts facts=old==null?new Facts(UUID.randomUUID().toString(),null,null,keepHistory,Map.of()):old.facts();
        facts=facts.confirm(first?"onFirstConfirmed":"onSecondConfirmed",at);
        if(old!=null && old.ride()!=null && !old.ride().vehicleId().equals(vehicleId))facts=facts.confirm("changedVehicle",at);
        State state=new State(UUID.randomUUID().toString(),form,option(form,firstLeg,secondLeg,old==null?at:old.option().leaveHome(),at),first?Phase.ON_24:Phase.ON_93,
            old==null?at:old.started(),at,old==null?Set.of():old.missed(),first && (prior==null || old.needs93()),false,facts,old==null?null:old.snapshot(),new Ride(vehicleId,route,at,driverConfirmed));
        return observe(state,now);
    }
    public String message(State state,Instant now) {
        if(state==null || state.ride()==null || state.phase()!=Phase.ON_24 && state.phase()!=Phase.ON_93)return "";
        var ride=state.ride();var feed=vehicles.get();var p=feed.positions().stream().filter(v->v.id().equals(ride.vehicleId()) && fresh(feed,v,now)).findFirst().orElse(null);
        if(p==null)return "On the "+ride.route()+" · vehicle "+ride.vehicleId()+" · location update overdue. Your selection is saved.";
        var leg=state.phase()==Phase.ON_24?state.option().first():state.option().second();
        if(!leg.alight().usable())return (ride.driverConfirmed()?"Driver-confirmed continuation. ":"Check with the driver before continuing. ")+"This vehicle's current trip has no confirmed onward arrival at your stop. Following the same vehicle through any turnaround; arrival remains unconfirmed.";
        return "Following vehicle "+ride.vehicleId()+" · location reported "+time(p.reportedAt())+" · arrival basis: "+leg.alight().source()+".";
    }
}
