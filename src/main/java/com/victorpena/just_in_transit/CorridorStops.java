package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.LocalDate;
import java.util.*;

/** Stops on trips that actually serve the fixed downtown transfer in the correct order. */
public final class CorridorStops {
    private CorridorStops() { }
    public static List<Stop> eligible(Schedule schedule,String kind,LocalDate date) {
        return eligible(schedule,kind,date,Corridor.of("work"));
    }
    public static List<Stop> eligible(Schedule schedule,String kind,LocalDate date,Corridor corridor) {
        if(schedule==null)return List.of();
        boolean origin="origin".equals(kind);
        String route=origin?corridor.firstRoute():corridor.secondRoute(), transfer=origin?corridor.transferOff():corridor.transferOn();
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
        return nearby(schedule,kind,date,lat,lon,Corridor.of("work"));
    }
    public static List<Nearby> nearby(Schedule schedule,String kind,LocalDate date,double lat,double lon,Corridor corridor) {
        return eligible(schedule,kind,date,corridor).stream().map(s->new Nearby(s.id(),s.name(),s.lat(),s.lon(),
                Math.round(meters(lat,lon,s.lat(),s.lon()))))
            .filter(s->meters(lat,lon,s.lat(),s.lon())<=2000)
            .sorted(Comparator.comparingLong(Nearby::straightLineMeters).thenComparing(Nearby::id)).toList();
    }
    /** Search the agency's actual stop names, while keeping date/direction/pickup restrictions. */
    public static List<Stop> search(Schedule schedule,String kind,LocalDate date,String query) {
        return search(schedule,kind,date,query,Corridor.of("work"));
    }
    public static List<Stop> search(Schedule schedule,String kind,LocalDate date,String query,Corridor corridor) {
        String normalized=normalize(query);
        if(normalized.isBlank())return List.of();
        List<String> words=Arrays.stream(normalized.split(" "))
            .filter(w->!Set.of("and","at","st","street","n","s","e","w","north","south","east","west").contains(w)).toList();
        if(words.isEmpty())return List.of();
        return eligible(schedule,kind,date,corridor).stream().filter(stop-> {
            if(stop.id().equalsIgnoreCase(query.strip()))return true;
            String name=normalize(stop.name());
            return words.stream().allMatch(name::contains);
        }).toList();
    }
    private static String normalize(String text) {
        return java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+"," ").strip();
    }
    public static double meters(double a,double b,double c,double d) {
        double lat=Math.toRadians(c-a),lon=Math.toRadians(d-b);
        double h=Math.pow(Math.sin(lat/2),2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.pow(Math.sin(lon/2),2);
        return 6371000*2*Math.asin(Math.sqrt(Math.min(1,h)));
    }
    public record Nearby(String id,String name,double lat,double lon,long straightLineMeters) { }
}
