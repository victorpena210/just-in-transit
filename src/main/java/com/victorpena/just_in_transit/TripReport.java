package com.victorpena.just_in_transit;

import jakarta.persistence.*;
import java.time.*;
import static com.victorpena.just_in_transit.TransitModels.*;

/** Opt-in summaries only: no addresses, coordinates or vehicle traces; scoped by an opaque browser digest. */
@Entity
public class TripReport {
    @Id public String id;
    @Column(length=64) public String owner;
    public String corridor;
    public LocalDate travelDate;
    public Instant plannedDeparture,actualDeparture,plannedArrival,actualArrival;
    public String transferOutcome;
    @Lob public String timingJson;
    @Column(length=1000) public String riderNote;
    @Column(columnDefinition="boolean default false") public boolean changedBuses;
    public record Timing(String event,Instant estimated,String source,Instant confirmed) {
        public String difference() {
            if(estimated==null || confirmed==null)return "Not recorded";
            long seconds=Duration.between(estimated,confirmed).getSeconds();
            return Math.abs(seconds)<60?"Within 1 min":(long)Math.ceil(Math.abs(seconds)/60.0)+" min "+(seconds>0?"later":"earlier");
        }
    }
    public java.util.List<Timing> timings() {
        if(timingJson==null)return java.util.List.of(
            new Timing("Departure",plannedDeparture,"Legacy estimate; source not retained",actualDeparture),
            new Timing("Arrival",plannedArrival,"Legacy estimate; source not retained",actualArrival));
        try { return java.util.Arrays.asList(tools.jackson.databind.json.JsonMapper.builder().build().readValue(timingJson,Timing[].class)); }
        catch(RuntimeException e) { return java.util.List.of(); }
    }
    public TripReport() { }
    public String title() { return Corridor.of(corridor).title(); }
    public String display(Instant value) { return value==null?"Not recorded":value.atZone(ZONE).toLocalDate()+" · "+time(value); }
    public String arrivalDifference() {
        if(plannedArrival==null || actualArrival==null)return "Not recorded";
        long seconds=Duration.between(plannedArrival,actualArrival).getSeconds();
        return Math.abs(seconds)<60?"Within one minute of plan":(long)Math.ceil(Math.abs(seconds)/60.0)+" min "+(seconds>0?"later":"earlier")+" than planned";
    }
}
