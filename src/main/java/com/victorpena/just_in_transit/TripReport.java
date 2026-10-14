package com.victorpena.just_in_transit;

import jakarta.persistence.*;
import java.time.*;
import static com.victorpena.just_in_transit.TransitModels.*;

/** Opt-in summaries only: no addresses, coordinates, vehicle traces or browser identifiers. */
@Entity
public class TripReport {
    @Id public String id;
    public String corridor;
    public LocalDate travelDate;
    public Instant plannedDeparture,actualDeparture,plannedArrival,actualArrival;
    public String transferOutcome;
    public TripReport() { }
    public String title() { return Corridor.of(corridor).title(); }
    public String display(Instant value) { return value==null?"Not recorded":value.atZone(ZONE).toLocalDate()+" · "+time(value); }
    public String arrivalDifference() {
        if(plannedArrival==null || actualArrival==null)return "Not recorded";
        long seconds=Duration.between(plannedArrival,actualArrival).getSeconds();
        return Math.abs(seconds)<60?"Within one minute of plan":(long)Math.ceil(Math.abs(seconds)/60.0)+" min "+(seconds>0?"later":"earlier")+" than planned";
    }
}
