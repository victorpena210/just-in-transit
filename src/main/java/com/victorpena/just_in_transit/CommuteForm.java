package com.victorpena.just_in_transit;

import java.time.LocalDate;
import java.time.LocalTime;
import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;

public record CommuteForm(
    @NotBlank @Size(max=200) String origin,
    @NotBlank @Size(max=200) String destination,
    @NotNull @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
    @NotNull @DateTimeFormat(iso=DateTimeFormat.ISO.TIME) LocalTime arriveBy,
    @NotNull @Min(0) @Max(120) Integer walkToStop,
    @NotNull @Min(0) @Max(30) Integer transferWalk,
    @NotNull @Min(0) @Max(120) Integer walkToWork,
    @NotNull @Min(1) @Max(30) Integer boardingBuffer,
    @NotNull @Min(1) @Max(30) Integer transferBuffer,
    @NotNull @Min(0) @Max(60) Integer arrivalBuffer,
    @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String destinationStop,
    @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String originStop,
    @Pattern(regexp="manual|address|origin|destination") String locationMode,
    @DecimalMin("-90") @DecimalMax("90") Double originLat,
    @DecimalMin("-180") @DecimalMax("180") Double originLon,
    @DecimalMin("-90") @DecimalMax("90") Double destinationLat,
    @DecimalMin("-180") @DecimalMax("180") Double destinationLon,
    @Size(max=200) String resolvedOrigin,
    @Size(max=200) String resolvedDestination,
    @Pattern(regexp="work|home") String corridor,
    @Pattern(regexp="arrive|depart") String planMode,
    @Pattern(regexp="home|stop|onboard") String startMode,
    @Size(max=200) String firstTrip
) {
    public CommuteForm {
        if(corridor==null)corridor="work";
        if(planMode==null)planMode="home".equals(corridor)?"depart":"arrive";
        if(startMode==null)startMode="home";
        if(firstTrip==null)firstTrip="";
        if(originStop==null)originStop=Corridor.of(corridor).defaultOrigin();
        if(locationMode==null)locationMode="manual";
    }
    public Corridor route() { return Corridor.of(corridor); }
    public boolean departing() { return "depart".equals(planMode); }
    public boolean atStop() { return "stop".equals(startMode); }
    public boolean onboard() { return "onboard".equals(startMode); }
    public CommuteForm withJourney(String mode,String trip) {
        return new CommuteForm(origin,destination,date,arriveBy,walkToStop,transferWalk,walkToWork,boardingBuffer,transferBuffer,arrivalBuffer,
            destinationStop,originStop,locationMode,originLat,originLon,destinationLat,destinationLon,resolvedOrigin,resolvedDestination,corridor,planMode,mode,trip);
    }
    public CommuteForm withTime(LocalDate day,LocalTime clock,String mode,String start,String trip) {
        return new CommuteForm(origin,destination,day,clock,walkToStop,transferWalk,walkToWork,boardingBuffer,transferBuffer,arrivalBuffer,
            destinationStop,originStop,locationMode,originLat,originLon,destinationLat,destinationLon,resolvedOrigin,resolvedDestination,corridor,mode,start,trip);
    }
    public CommuteForm(String origin,String destination,LocalDate date,LocalTime arriveBy,Integer walkToStop,Integer transferWalk,Integer walkToWork,
        Integer boardingBuffer,Integer transferBuffer,Integer arrivalBuffer,String destinationStop,String originStop,String locationMode,
        Double originLat,Double originLon,Double destinationLat,Double destinationLon,String resolvedOrigin,String resolvedDestination) {
        this(origin,destination,date,arriveBy,walkToStop,transferWalk,walkToWork,boardingBuffer,transferBuffer,arrivalBuffer,destinationStop,
            originStop,locationMode,originLat,originLon,destinationLat,destinationLon,resolvedOrigin,resolvedDestination,"work","arrive","home","");
    }
    // Backward-compatible constructor for saved settings and callers of the original planner.
    public CommuteForm(String origin,String destination,LocalDate date,LocalTime arriveBy,
                       Integer walkToStop,Integer transferWalk,Integer walkToWork,Integer boardingBuffer,
                       Integer transferBuffer,Integer arrivalBuffer,String destinationStop) {
        this(origin,destination,date,arriveBy,walkToStop,transferWalk,walkToWork,boardingBuffer,
            transferBuffer,arrivalBuffer,destinationStop,"81799","manual",null,null,null,null,null,null);
    }
    /** Each end can use a confirmed map point or a manually checked walk independently. */
    public boolean usesAddress(String kind) {
        return "address".equals(locationMode) || kind.equals(locationMode);
    }
    @AssertTrue(message="Confirm each searched location on the map, or choose Use stop and walking minutes for that location.")
    public boolean isLocationResolved() {
        return (!usesAddress("origin") || originLat!=null && originLon!=null
            && java.util.Objects.equals(origin,resolvedOrigin))
            && (!usesAddress("destination") || destinationLat!=null && destinationLon!=null
            && java.util.Objects.equals(destination,resolvedDestination));
    }
}
