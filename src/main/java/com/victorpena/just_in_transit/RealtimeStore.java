package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import com.google.transit.realtime.GtfsRealtime.*;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RealtimeStore {
    private final String url;
    private final boolean enabled;
    private Instant attempted=Instant.EPOCH;
    private Snapshot snapshot=Snapshot.empty();
    private String message="Live data has not loaded yet.";
    public RealtimeStore(@Value("${via.realtime-url}") String url,@Value("${via.enabled:true}") boolean enabled) {
        this.url=url;this.enabled=enabled;
    }
    public synchronized Snapshot get() {
        Instant now=Instant.now();
        if(!enabled || attempted.plusSeconds(30).isAfter(now))return snapshot;
        attempted=now;
        try {
            snapshot=Snapshot.parse(FeedHttp.fetch(url,15_000_000));
            message=snapshot.fresh(now)?"VIA live feed connected.":"Live feed is stale. Showing schedule fallbacks.";
        } catch(Exception e) {
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            message="Live download unavailable. Recent estimates are used only while fresh; otherwise scheduled times appear.";
        }
        return snapshot;
    }
    public String message() { return message; }
    public record Snapshot(Instant timestamp,Map<String,List<TripUpdate>> trips) {
        public static Snapshot empty() { return new Snapshot(Instant.EPOCH,Map.of()); }
        public static Snapshot parse(byte[] bytes) throws IOException {
            FeedMessage feed=FeedMessage.parseFrom(bytes);
            if(feed.getHeader().getIncrementality()==FeedHeader.Incrementality.DIFFERENTIAL)
                throw new IOException("Differential feeds are not supported");
            Map<String,List<TripUpdate>> trips=new HashMap<>();
            for(var entity:feed.getEntityList())if(!entity.getIsDeleted() && entity.hasTripUpdate()) {
                var update=entity.getTripUpdate();
                trips.computeIfAbsent(update.getTrip().getTripId(),key->new ArrayList<>()).add(update);
            }
            return new Snapshot(Instant.ofEpochSecond(feed.getHeader().getTimestamp()),trips);
        }
        public boolean fresh(Instant now) { return recent(timestamp,now); }
        private static boolean recent(Instant stamp,Instant now) {
            long age=Duration.between(stamp,now).getSeconds();return age>=-30 && age<=120;
        }
        public String updatedDisplay() {
            return timestamp.equals(Instant.EPOCH)?"No live snapshot":DateTimeFormatter.ofPattern("MMM d, h:mm:ss a z").withZone(ZONE).format(timestamp);
        }
        public Event event(Trip trip,LocalDate day,StopTime stop,boolean boarding,Instant now) {
            Instant scheduled=serviceTime(day,boarding?stop.departure():stop.arrival());
            Event fallback=new Event(scheduled,"Scheduled",false,true);
            if(!fresh(now))return fallback;
            String serviceDate=day.format(DateTimeFormatter.BASIC_ISO_DATE);
            TripUpdate update=trips.getOrDefault(trip.id(),List.of()).stream().filter(u->
                u.getTrip().hasStartDate()?u.getTrip().getStartDate().equals(serviceDate):day.equals(timestamp.atZone(ZONE).toLocalDate())
            ).findFirst().orElse(null);
            if(update==null)return fallback;
            // Never use an old trip estimate merely because the enclosing feed is new.
            if(update.hasTimestamp() && !recent(Instant.ofEpochSecond(update.getTimestamp()),now))return fallback;
            String relationship=update.getTrip().getScheduleRelationship().name();
            if(!relationship.equals("SCHEDULED"))return new Event(scheduled,"Trip unavailable",false,false);
            for(var s:update.getStopTimeUpdateList()) {
                boolean match=s.hasStopSequence()?s.getStopSequence()==stop.sequence():s.getStopId().equals(stop.id());
                if(!match)continue;
                if(s.getScheduleRelationship()==TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED)
                    return new Event(scheduled,"Stop skipped",false,false);
                if(s.getScheduleRelationship()==TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA)return fallback;
                TripUpdate.StopTimeEvent event=null;
                String source="Live estimate";
                if(boarding && s.hasDeparture())event=s.getDeparture();
                else if(s.hasArrival()) {
                    event=s.getArrival();
                    if(boarding) { source="Live arrival · boarding proxy";scheduled=serviceTime(day,stop.arrival()); }
                }
                if(event==null)return fallback;
                Instant predicted=event.hasTime()?Instant.ofEpochSecond(event.getTime()):event.hasDelay()?scheduled.plusSeconds(event.getDelay()):null;
                // A mismatched service day must not become a spurious live recommendation.
                if(predicted==null || Math.abs(Duration.between(scheduled,predicted).toHours())>6)return fallback;
                return new Event(predicted,source,true,true);
            }
            // This prototype deliberately does not propagate one stop's delay to other stops.
            return fallback;
        }
    }
}
