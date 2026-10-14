package com.victorpena.just_in_transit;

import com.google.transit.realtime.GtfsRealtime.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One shared, rate-limited VIA fetch; positions never determine arrival predictions. */
@Component
public class VehicleStore {
    private final String url;
    private final boolean enabled;
    private Instant attempted=Instant.EPOCH;
    private Snapshot snapshot=Snapshot.empty();
    private String message="Vehicle positions have not loaded yet.";
    public VehicleStore(@Value("${via.vehicles-url:http://gtfs.viainfo.net/vehicle/vehiclepositions.pb}") String url,
                        @Value("${via.enabled:true}") boolean enabled) { this.url=url;this.enabled=enabled; }
    public synchronized Snapshot get() {
        Instant now=Instant.now();
        if(!enabled){message="Vehicle lookups are disabled.";return snapshot;}
        if(attempted.plusSeconds(30).isAfter(now))return snapshot;
        attempted=now;
        try { snapshot=Snapshot.parse(FeedHttp.fetch(url,15_000_000));message="VIA vehicle feed checked."; }
        catch(Exception e) {
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            message="Vehicle download unavailable. Any remaining markers show their last reported position.";
        }
        return snapshot;
    }
    public synchronized String message() { return message; }
    public record Position(String id,String tripId,String route,String serviceDate,double lat,double lon,Instant reportedAt) { }
    public record Snapshot(Instant timestamp,List<Position> positions) {
        public static Snapshot empty() { return new Snapshot(Instant.EPOCH,List.of()); }
        public static Snapshot parse(byte[] bytes) throws IOException {
            try {
                FeedMessage feed=FeedMessage.parseFrom(bytes);
                if(feed.getHeader().getIncrementality()==FeedHeader.Incrementality.DIFFERENTIAL)
                    throw new IOException("Differential vehicle feeds are not supported");
                List<Position> positions=new ArrayList<>();Set<String> seen=new HashSet<>();
                for(var entity:feed.getEntityList()) {
                    if(entity.getIsDeleted() || !entity.hasVehicle())continue;
                    var v=entity.getVehicle();if(!v.hasPosition() || !v.hasTrip())continue;
                    var p=v.getPosition();if(!p.hasLatitude() || !p.hasLongitude() || !MapService.inArea(p.getLatitude(),p.getLongitude()))continue;
                    var t=v.getTrip();if(t.getTripId().isBlank() || t.getScheduleRelationship()!=TripDescriptor.ScheduleRelationship.SCHEDULED)continue;
                    String id=v.hasVehicle() && !v.getVehicle().getId().isBlank()?v.getVehicle().getId():entity.getId();
                    if(id.isBlank() || !seen.add(id))continue;
                    positions.add(new Position(id,t.getTripId(),t.getRouteId(),t.getStartDate(),p.getLatitude(),p.getLongitude(),
                        v.hasTimestamp() && v.getTimestamp()>0?Instant.ofEpochSecond(v.getTimestamp()):null));
                }
                return new Snapshot(Instant.ofEpochSecond(feed.getHeader().getTimestamp()),List.copyOf(positions));
            } catch(RuntimeException e) { throw new IOException("Invalid vehicle feed",e); }
        }
    }
}
