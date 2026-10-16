package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Presentation data for the selected service instance; no location tracking is stored. */
public final class LiveMapData {
    private LiveMapData() { }
    public record Bus(String id,String tripId,String route,String headsign,double lat,double lon,
                      Instant reportedAt,Instant validUntil,boolean fresh,boolean selected) { }
    public record Countdown(String id,String label,Instant target,String source,Instant validUntil,String passed) { }
    public static List<Bus> buses(VehicleStore.Snapshot feed,Schedule schedule,String origin,String destination,
                                  Option selected,Instant now) {
        return buses(feed,schedule,origin,destination,selected,now,Corridor.of("work"));
    }
    public static List<Bus> buses(VehicleStore.Snapshot feed,Schedule schedule,String origin,String destination,Option selected,Instant now,Corridor route) {
        if(schedule==null || !recent(feed.timestamp(),now,600))return List.of();
        Map<String,Trip> trips=new HashMap<>();schedule.trips().forEach(t->trips.put(t.id(),t));
        List<Bus> result=new ArrayList<>();
        for(var v:feed.positions()) {
            Trip trip=trips.get(v.tripId());if(trip==null || !Set.of("24","93").contains(trip.route()))continue;
            if(!v.route().isBlank() && !v.route().equals(trip.route()))continue;
            String from=trip.route().equals(route.firstRoute())?origin:"76773",to=trip.route().equals(route.firstRoute())?"76779":destination;
            if(!serves(trip,from,to))continue;
            Instant stamp=v.reportedAt()==null?feed.timestamp():v.reportedAt();
            if(!recent(stamp,now,600))continue;
            Instant until=min(feed.timestamp().plusSeconds(90),stamp.plusSeconds(90));
            boolean fresh=v.reportedAt()!=null && recent(feed.timestamp(),now,90) && recent(stamp,now,90);
            Leg leg=selected==null?null:trip.route().equals(route.firstRoute())?selected.first():selected.second();
            // Without a start date, recurring and overnight trips cannot be matched confidently.
            boolean match=leg!=null && leg.tripId().equals(v.tripId()) && leg.serviceDate().format(DateTimeFormatter.BASIC_ISO_DATE).equals(v.serviceDate());
            result.add(new Bus(v.id(),v.tripId(),trip.route(),trip.headsign(),v.lat(),v.lon(),v.reportedAt(),until,fresh,match));
        }
        return List.copyOf(result);
    }
    /** Both directions remain selectable, including a vehicle on its downtown turnaround. */
    public static List<Bus> candidates(VehicleStore.Snapshot feed,Schedule schedule,Instant now) {
        if(schedule==null || !recent(feed.timestamp(),now,600))return List.of();
        Map<String,Trip> trips=new HashMap<>();schedule.trips().forEach(t->trips.put(t.id(),t));
        var result=new ArrayList<Bus>();
        for(var p:feed.positions()) {
            var t=trips.get(p.tripId());
            if(t==null || !Set.of("24","93").contains(t.route()) || !p.route().isBlank() && !p.route().equals(t.route()))continue;
            Instant stamp=p.reportedAt()==null?feed.timestamp():p.reportedAt();
            if(!recent(stamp,now,600))continue;
            var day=RiderTracking.day(p);
            boolean fresh=day!=null && schedule.active(t.service(),day) && RiderTracking.fresh(feed,p,now);
            result.add(new Bus(p.id(),p.tripId(),t.route(),t.headsign(),p.lat(),p.lon(),p.reportedAt(),min(feed.timestamp().plusSeconds(90),stamp.plusSeconds(90)),fresh,false));
        }
        return List.copyOf(result);
    }
    private static boolean serves(Trip trip,String from,String to) {
        boolean boarded=false;
        for(var s:trip.stops()) { if(boarded && s.id().equals(to) && s.dropoff())return true;
            if(s.id().equals(from) && s.pickup())boarded=true; }
        return false;
    }
    private static boolean recent(Instant t,Instant now,long max) { long age=Duration.between(t,now).getSeconds();return age>=-30 && age<=max; }
    private static Instant min(Instant a,Instant b) { return a.isBefore(b)?a:b; }
    private static Instant expires(Leg leg,LiveSnapshot live,Instant now) {
        Instant end=now.plusSeconds(75);
        if(!leg.board().live() && !leg.alight().live())return end;
        end=min(end,live.timestamp().plusSeconds(120));
        String day=leg.serviceDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        for(var t:live.trips().getOrDefault(leg.tripId(),List.of()))if(t.timestamp()!=null &&
            (day.equals(t.startDate()) || t.startDate()==null && leg.serviceDate().equals(live.timestamp().atZone(ZONE).toLocalDate())))
            end=min(end,t.timestamp().plusSeconds(120));
        return end;
    }
    public static List<Countdown> countdowns(CommuteForm f,Option o,ActiveJourney.View active,LiveSnapshot live,Instant now) {
        if(o==null || active!=null && active.finished())return List.of();
        List<Countdown> result=new ArrayList<>();
        boolean all=o.first().board().usable() && o.first().alight().usable() && o.second().board().usable() && o.second().alight().usable();
        Instant firstExpiry=expires(o.first(),live,now),secondExpiry=expires(o.second(),live,now);
        if(active==null && !f.atStop() && !f.onboard())result.add(new Countdown("leave","Leave in",all?o.leaveHome():null,
            "Based on "+f.route().firstRoute()+": "+o.first().board().source()+" · "+f.walkToStop()+" min walk + "+f.boardingBuffer()+" min boarding buffer",
            min(firstExpiry,secondExpiry),"Leave-time estimate passed — select your current stage or replan"));
        if(active==null && !f.onboard() || active!=null && !active.firstBoardDone() && !active.state().needs24())add(result,"bus24",f.route().firstRoute()+" boarding in",o.first().board(),firstExpiry);
        else if(active==null && f.onboard() || active!=null && !active.firstDone())add(result,"transfer","At Martin & Leona in",o.first().alight(),firstExpiry);
        if(active==null || !active.secondBoardDone()) {
            if(active==null || active.hasConnection())add(result,"bus93",f.route().secondRoute()+" boarding in",o.second().board(),secondExpiry);
        } else if(!active.secondDone())add(result,"last-stop","At your "+f.route().secondRoute()+" stop in",o.second().alight(),secondExpiry);
        boolean arrival=active==null?all:active.arrivalAvailable();
        String source=active!=null && active.secondDone()?"Your confirmed bus arrival + entered final walk":f.route().secondRoute()+": "+o.second().alight().source()+" + final walk";
        result.add(new Countdown("work","At your destination in",arrival?o.atWork():null,source,
            active!=null && active.secondDone()?now.plusSeconds(75):min(firstExpiry,secondExpiry),"Arrival estimate passed — confirm your progress"));
        return List.copyOf(result);
    }
    private static void add(List<Countdown> result,String id,String label,Event e,Instant expires) {
        result.add(new Countdown(id,label,e.usable()?e.at():null,e.source(),expires,"Estimate passed — check VIA or confirm boarding"));
    }
}
