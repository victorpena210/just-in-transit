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
    public static final class Snapshot extends LiveSnapshot {
        public Snapshot(Instant timestamp,Map<String,List<TripPrediction>> trips) { super(timestamp,trips); }
        public static Snapshot empty() { return new Snapshot(Instant.EPOCH,Map.of()); }
        public static Snapshot parse(byte[] bytes) throws IOException {
            FeedMessage feed=FeedMessage.parseFrom(bytes);
            if(feed.getHeader().getIncrementality()==FeedHeader.Incrementality.DIFFERENTIAL)
                throw new IOException("Differential feeds are not supported");
            Map<String,List<TripPrediction>> trips=new HashMap<>();
            for(var entity:feed.getEntityList())if(!entity.getIsDeleted() && entity.hasTripUpdate()) {
                var u=entity.getTripUpdate();var descriptor=u.getTrip();
                var stops=u.getStopTimeUpdateList().stream().map(s->new StopPrediction(
                    s.hasStopSequence()?s.getStopSequence():null,s.getStopId(),s.getScheduleRelationship().name(),
                    s.hasArrival()?prediction(s.getArrival()):null,s.hasDeparture()?prediction(s.getDeparture()):null)).toList();
                var trip=new TripPrediction(descriptor.hasStartDate()?descriptor.getStartDate():null,
                    descriptor.getScheduleRelationship().name(),u.hasTimestamp()?Instant.ofEpochSecond(u.getTimestamp()):null,
                    u.hasDelay()?(long)u.getDelay():null,stops);
                trips.computeIfAbsent(descriptor.getTripId(),key->new ArrayList<>()).add(trip);
            }
            return new Snapshot(Instant.ofEpochSecond(feed.getHeader().getTimestamp()),trips);
        }
        private static Prediction prediction(TripUpdate.StopTimeEvent event) {
            return new Prediction(event.hasTime()?Instant.ofEpochSecond(event.getTime()):null,
                event.hasDelay()?(long)event.getDelay():null,event.hasUncertainty()?event.getUncertainty():null);
        }
    }
}
