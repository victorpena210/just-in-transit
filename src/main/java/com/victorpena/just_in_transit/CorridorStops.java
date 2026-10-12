package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.LocalDate;
import java.util.*;

/** Stops on trips that actually serve the fixed downtown transfer in the correct order. */
public final class CorridorStops {
    private CorridorStops() { }
    public static List<Stop> eligible(Schedule schedule,String kind,LocalDate date) {
        if(schedule==null)return List.of();
        boolean origin="origin".equals(kind);
        String route=origin?"24":"93", transfer=origin?"76779":"76773";
        Set<String> ids=new HashSet<>();
        for(Trip trip:schedule.trips()) {
            boolean active=schedule.active(trip.service(),date) || schedule.active(trip.service(),date.minusDays(1))
                && trip.stops().stream().anyMatch(s->s.arrival()>=86400 || s.departure()>=86400);
            if(!trip.route().equals(route) || !active)continue;
            var stops=trip.stops();
            for(int i=0;i<stops.size();i++) {
                StopTime at=stops.get(i);
                if(!at.id().equals(transfer) || !(origin?at.dropoff():at.pickup()))continue;
                for(int j=origin?0:i+1;j<(origin?i:stops.size());j++) {
                    StopTime s=stops.get(j);
                    if((origin?s.pickup():s.dropoff()) && !s.id().equals(transfer))ids.add(s.id());
                }
            }
        }
        return ids.stream().map(schedule.stops()::get).filter(Objects::nonNull)
            .sorted(Comparator.comparing(Stop::name).thenComparing(Stop::id)).toList();
    }
    public static List<Nearby> nearby(Schedule schedule,String kind,LocalDate date,double lat,double lon) {
        return eligible(schedule,kind,date).stream().map(s->new Nearby(s.id(),s.name(),s.lat(),s.lon(),
                Math.round(meters(lat,lon,s.lat(),s.lon()))))
            .filter(s->s.straightLineMeters()<=2000).sorted(Comparator.comparingLong(Nearby::straightLineMeters))
            .limit(5).toList();
    }
    public static double meters(double a,double b,double c,double d) {
        double lat=Math.toRadians(c-a),lon=Math.toRadians(d-b);
        double h=Math.pow(Math.sin(lat/2),2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.pow(Math.sin(lon/2),2);
        return 6371000*2*Math.asin(Math.sqrt(Math.min(1,h)));
    }
    public record Nearby(String id,String name,double lat,double lon,long straightLineMeters) { }
}
