package com.victorpena.just_in_transit;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class TransitModels {
    private TransitModels() { }
    public static final ZoneId ZONE = ZoneId.of("America/Chicago");
    public static String time(Instant instant) {
        return DateTimeFormatter.ofPattern("h:mm a").withZone(ZONE).format(instant);
    }
    // GTFS defines service-day time from local noon minus twelve hours (including DST days).
    public static Instant serviceTime(LocalDate date, int seconds) {
        return date.atTime(12,0).atZone(ZONE).toInstant().minusSeconds(43200).plusSeconds(seconds);
    }
    public record Stop(String id, String name, double lat, double lon) { }
    public record StopTime(String id, int sequence, int arrival, int departure, boolean pickup, boolean dropoff) { }
    public record Trip(String id, String route, String service, String headsign, List<StopTime> stops, String direction) {
        public Trip(String id,String route,String service,String headsign,List<StopTime> stops) {
            this(id,route,service,headsign,stops,"");
        }
    }
    public record Service(LocalDate start, LocalDate end, Set<DayOfWeek> days) {
        public boolean active(LocalDate date) { return !date.isBefore(start) && !date.isAfter(end) && days.contains(date.getDayOfWeek()); }
    }
    public record Schedule(Map<String, Stop> stops, List<Trip> trips, Map<String,Service> services,
                           Map<String,Map<LocalDate,Boolean>> exceptions,Map<String,RouteInfo> routes) {
        public Schedule(Map<String,Stop> stops,List<Trip> trips,Map<String,Service> services,
                        Map<String,Map<LocalDate,Boolean>> exceptions) { this(stops,trips,services,exceptions,Map.of()); }
        public boolean active(String service, LocalDate date) {
            Boolean override=exceptions.getOrDefault(service,Map.of()).get(date);
            return override!=null ? override : services.containsKey(service) && services.get(service).active(date);
        }
    }
    public record RouteInfo(String agency,int type) { }
    public record Event(Instant at, String source, boolean live, boolean usable) {
        public String display() { return time(at); }
    }
    public record Leg(String route, String tripId, Event board, Event alight,LocalDate serviceDate) { }
    public record Option(Leg first, Leg second, Instant leaveHome, Instant atWork,
                         long transferMargin, long arrivalMargin, long transferWait,
                         boolean meetsBuffers, String rating, Long nextBusMinutes, Backup backup) {
        public String leaveDisplay() { return time(leaveHome); }
        public String workDisplay() { return time(atWork); }
        public String dataLabel() {
            long count=List.of(first.board(),first.alight(),second.board(),second.alight()).stream().filter(Event::live).count();
            return count==4 ? "Live estimates" : count==0 ? "Scheduled times" : "Live + scheduled times";
        }
        public String qualityClass() { return meetsBuffers ? "comfortable" : "tight"; }
    }
    public record Backup(Leg leg,Instant atWork,long minutesLater,long arrivalSeconds) {
        public String departureDisplay() { return leg.board().display(); }
        public String workDisplay() { return time(atWork); }
        public boolean onTime() { return arrivalSeconds>=0; }
        public String deadlineLabel() {
            if(arrivalSeconds<0)return (long)Math.ceil(-arrivalSeconds/60.0)+" min late";
            if(arrivalSeconds<60)return "At your deadline";
            return arrivalSeconds/60+" min before your deadline";
        }
        public String dataLabel() { return leg.board().live()&&leg.alight().live()?"Live estimates":
            leg.board().live()||leg.alight().live()?"Live + scheduled times":"Scheduled times"; }
    }
    public record BoardRow(String route,String stop,Event event,String tripId) { }
    public record Result(List<Option> options,String message) { }
}
