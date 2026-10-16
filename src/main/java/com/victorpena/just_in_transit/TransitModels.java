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
                           Map<String,Map<LocalDate,Boolean>> exceptions,Map<String,RouteInfo> routes,List<Transfer> transfers) {
        public Schedule(Map<String,Stop> stops,List<Trip> trips,Map<String,Service> services,Map<String,Map<LocalDate,Boolean>> exceptions,Map<String,RouteInfo> routes) { this(stops,trips,services,exceptions,routes,List.of()); }
        public Schedule(Map<String,Stop> stops,List<Trip> trips,Map<String,Service> services,
                        Map<String,Map<LocalDate,Boolean>> exceptions) { this(stops,trips,services,exceptions,Map.of()); }
        public boolean active(String service, LocalDate date) {
            Boolean override=exceptions.getOrDefault(service,Map.of()).get(date);
            return override!=null ? override : services.containsKey(service) && services.get(service).active(date);
        }
    }
    public record Transfer(String from,String to,int type,int minimumSeconds,String fromRoute,String toRoute,String fromTrip,String toTrip) {
        public boolean matches(Trip a,Trip b) { return (fromRoute.isBlank() || fromRoute.equals(a.route())) && (toRoute.isBlank() || toRoute.equals(b.route())) && (fromTrip.isBlank() || fromTrip.equals(a.id())) && (toTrip.isBlank() || toTrip.equals(b.id())); }
    }
    public record RouteInfo(String agency,int type) { }
    public record Event(Instant at, String source, boolean live, boolean usable) {
        public String display() { return time(at); }
        public boolean directPrediction() { return live && usable && source.equals("Live estimate"); }
    }
    public record Leg(String route, String tripId, Event board, Event alight,LocalDate serviceDate) { }
    public record Option(Leg first, Leg second, Instant leaveHome, Instant atWork,
                         long transferMargin, long arrivalMargin, long transferWait,
                         boolean meetsBuffers, String rating, Long nextBusMinutes, Backup backup,boolean hasDeadline) {
        public Option(Leg first,Leg second,Instant leaveHome,Instant atWork,long transferMargin,long arrivalMargin,long transferWait,
            boolean meetsBuffers,String rating,Long nextBusMinutes,Backup backup) {
            this(first,second,leaveHome,atWork,transferMargin,arrivalMargin,transferWait,meetsBuffers,rating,nextBusMinutes,backup,true);
        }
        public CommuteTiming.Summary timing(CommuteForm form) { return CommuteTiming.summarize(form,this); }
        public String deadlineLabel() { return !hasDeadline?"No arrival deadline set":arrivalMargin<0?(-arrivalMargin)+" min late":arrivalMargin==0?"At your deadline":arrivalMargin+" min before your deadline"; }
        public String backupSummary() { return backup==null?"No later matching "+second.route()+" found in the checked service days.":
            "If you miss this "+second.route()+": estimated arrival "+backup.workDisplay()+" · "+backup.deadlineLabel()+"."; }
        public String comparisonLabel(Option best) {
            if(equals(best))return !hasDeadline?(meetsBuffers?"Recommended departure":"Closest option"):arrivalMargin<0?"Earliest remaining arrival":meetsBuffers?"Best fit":"Closest option · outside preferences";
            if(arrivalMargin<0)return "Later arrival";
            if(first.equals(best.first))return "Different transfer";
            if(leaveHome.isAfter(best.leaveHome))return "Leave later";
            if(leaveHome.isBefore(best.leaveHome) && (transferMargin>best.transferMargin || arrivalMargin>best.arrivalMargin))return "More breathing room";
            return "Earlier departure";
        }
        public String comparisonDetail(Option best) {
            if(equals(best))return meetsBuffers?"Meets your arrival window, walks, waits and delay allowance.":arrivalMargin<0?"Earliest arrival on your saved route; your deadline stays unchanged.":"Outside your preferences; review the arrival window, waits and delay allowance.";
            long difference=Duration.between(best.leaveHome,leaveHome).toMinutes();
            return (difference>0?difference+" min longer at home":(-difference)+" min earlier departure")+" · "+transferMargin+" min after the transfer walk · "+deadlineLabel()+".";
        }
        public String leaveDisplay() { return time(leaveHome); }
        public String workDisplay() { return time(atWork); }
        public String dataLabel() {
            long count=List.of(first.board(),first.alight(),second.board(),second.alight()).stream().filter(Event::live).count();
            return count==4 ? "Live estimates" : count==0 ? "Scheduled times" : "Live + scheduled times";
        }
        public boolean connectionConfirmed() { return first.alight().directPrediction() && second.board().directPrediction(); }
        public String displayRating() { return meetsBuffers && !connectionConfirmed()?"Preferences met · estimated":rating; }
        public String confidenceWarning() {
            return connectionConfirmed()?"Live transfer estimates are available; they can still change.":
                "Connection unconfirmed: transfer timing includes scheduled, carried-forward, proxy, or uncertain estimates. Missing live data does not mean the bus is on time.";
        }
        public String qualityClass() { return !meetsBuffers?"tight":connectionConfirmed()?"comfortable":"uncertain"; }
    }
    public record Backup(Leg leg,Instant atWork,long minutesLater,long arrivalSeconds,boolean hasDeadline) {
        public Backup(Leg leg,Instant atWork,long minutesLater,long arrivalSeconds) { this(leg,atWork,minutesLater,arrivalSeconds,true); }
        public String departureDisplay() { return leg.board().display(); }
        public String workDisplay() { return time(atWork); }
        public boolean onTime() { return !hasDeadline || arrivalSeconds>=0; }
        public String deadlineLabel() {
            if(!hasDeadline)return "No arrival deadline set";
            if(arrivalSeconds<0)return (long)Math.ceil(-arrivalSeconds/60.0)+" min late";
            if(arrivalSeconds<60)return "At your deadline";
            return arrivalSeconds/60+" min before your deadline";
        }
        public String dataLabel() { return leg.board().live()&&leg.alight().live()?"Live estimates":
            leg.board().live()||leg.alight().live()?"Live + scheduled times":"Scheduled times"; }
    }
    public record BoardRow(String route,String stop,Event event,String tripId) { }
    public record Result(List<Option> options,String message) { public boolean lateFallback() { return !options.isEmpty() && options.get(0).arrivalMargin()<0; } }
}
