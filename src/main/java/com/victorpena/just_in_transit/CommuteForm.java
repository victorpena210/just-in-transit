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
    @Size(max=200) String firstTrip,
    @Min(1) @Max(120) Integer maxBoardingWait,
    @Min(1) @Max(120) Integer maxTransferWait,
    @Min(0) @Max(60) Integer trafficAllowance,
    @Pattern(regexp="balanced|latest|safer") String preference,
    @Min(0) @Max(360) Integer maxEarlyArrival
) {
    public CommuteForm {
        if(maxBoardingWait==null)maxBoardingWait=Math.max(15,boardingBuffer==null?0:boardingBuffer);
        if(maxTransferWait==null)maxTransferWait=Math.max(30,transferBuffer==null?0:transferBuffer);
        if(trafficAllowance==null)trafficAllowance=0;
        if(preference==null)preference="latest";
        if(maxEarlyArrival==null)maxEarlyArrival=Math.max(15,(arrivalBuffer==null?0:arrivalBuffer)+trafficAllowance);
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
            destinationStop,originStop,locationMode,originLat,originLon,destinationLat,destinationLon,resolvedOrigin,resolvedDestination,corridor,planMode,mode,trip,maxBoardingWait,maxTransferWait,trafficAllowance,preference,maxEarlyArrival);
    }
    public CommuteForm withTime(LocalDate day,LocalTime clock,String mode,String start,String trip) {
        return new CommuteForm(origin,destination,day,clock,walkToStop,transferWalk,walkToWork,boardingBuffer,transferBuffer,arrivalBuffer,
            destinationStop,originStop,locationMode,originLat,originLon,destinationLat,destinationLon,resolvedOrigin,resolvedDestination,corridor,mode,start,trip,maxBoardingWait,maxTransferWait,trafficAllowance,preference,maxEarlyArrival);
    }
    @AssertTrue(message="Maximum waits must be at least the minimum first-stop and transfer buffers.")
    public boolean isWaitWindowValid() {
        return (boardingBuffer==null || maxBoardingWait>=boardingBuffer)
            && (transferBuffer==null || maxTransferWait>=transferBuffer);
    }
    public CommuteForm withWaitLimits(int firstMax,int transferMax) { return withPreferences(firstMax,transferMax,trafficAllowance,preference); }
    public CommuteForm withPreferences(int firstMax,int transferMax,int traffic,String ranking) {
        return new CommuteForm(origin,destination,date,arriveBy,walkToStop,transferWalk,walkToWork,boardingBuffer,transferBuffer,arrivalBuffer,
            destinationStop,originStop,locationMode,originLat,originLon,destinationLat,destinationLon,resolvedOrigin,resolvedDestination,
            corridor,planMode,startMode,firstTrip,firstMax,transferMax,traffic,ranking,maxEarlyArrival);
    }
    @AssertTrue(message="The maximum early arrival must cover your arrival cushion plus the extra delay allowance.")
    public boolean isArrivalWindowValid() {
        return departing() || arrivalBuffer==null || maxEarlyArrival>=arrivalBuffer+trafficAllowance;
    }
    public CommuteForm withArrivalWindow(int early,int maximum) {
        return new CommuteForm(origin,destination,date,arriveBy,walkToStop,transferWalk,walkToWork,boardingBuffer,transferBuffer,early,
            destinationStop,originStop,locationMode,originLat,originLon,destinationLat,destinationLon,resolvedOrigin,resolvedDestination,
            corridor,planMode,startMode,firstTrip,maxBoardingWait,maxTransferWait,trafficAllowance,preference,maximum);
    }
    public CommuteForm withWaitWindows(int firstMin,int firstMax,int transferMin,int transferMax) {
        return new CommuteForm(origin,destination,date,arriveBy,walkToStop,transferWalk,walkToWork,firstMin,transferMin,arrivalBuffer,
            destinationStop,originStop,locationMode,originLat,originLon,destinationLat,destinationLon,resolvedOrigin,resolvedDestination,
            corridor,planMode,startMode,firstTrip,firstMax,transferMax,trafficAllowance,preference,maxEarlyArrival);
    }
    public String arrivalWindowLabel() {
        if(departing())return "Leave after your selected time; no arrival window set.";
        if(date==null || arriveBy==null || arrivalBuffer==null)return "Choose your arrival deadline and early-arrival limits.";
        var deadline=date.atTime(arriveBy).atZone(TransitModels.ZONE).toInstant();
        return "Destination arrival window: "+TransitModels.time(deadline.minusSeconds(maxEarlyArrival*60L))+"–"
            +TransitModels.time(deadline.minusSeconds((arrivalBuffer+trafficAllowance)*60L))+" · includes your final walk"
            +(trafficAllowance>0?" and "+trafficAllowance+" min delay allowance":"")+".";
    }
    public CommuteForm(String origin,String destination,LocalDate date,LocalTime arriveBy,Integer walkToStop,Integer transferWalk,Integer walkToWork,
        Integer boardingBuffer,Integer transferBuffer,Integer arrivalBuffer,String destinationStop,String originStop,String locationMode,
        Double originLat,Double originLon,Double destinationLat,Double destinationLon,String resolvedOrigin,String resolvedDestination,
        String corridor,String planMode,String startMode,String firstTrip,Integer maxBoardingWait,Integer maxTransferWait,Integer trafficAllowance,String preference) {
        this(origin,destination,date,arriveBy,walkToStop,transferWalk,walkToWork,boardingBuffer,transferBuffer,arrivalBuffer,destinationStop,
            originStop,locationMode,originLat,originLon,destinationLat,destinationLon,resolvedOrigin,resolvedDestination,corridor,planMode,startMode,firstTrip,
            maxBoardingWait,maxTransferWait,trafficAllowance,preference,null);
    }
    // Existing saved checkpoints and callers keep the original zero extra-delay assumption.
    public CommuteForm(String origin,String destination,LocalDate date,LocalTime arriveBy,Integer walkToStop,Integer transferWalk,Integer walkToWork,
        Integer boardingBuffer,Integer transferBuffer,Integer arrivalBuffer,String destinationStop,String originStop,String locationMode,
        Double originLat,Double originLon,Double destinationLat,Double destinationLon,String resolvedOrigin,String resolvedDestination,
        String corridor,String planMode,String startMode,String firstTrip) {
        this(origin,destination,date,arriveBy,walkToStop,transferWalk,walkToWork,boardingBuffer,transferBuffer,arrivalBuffer,destinationStop,
            originStop,locationMode,originLat,originLon,destinationLat,destinationLon,resolvedOrigin,resolvedDestination,corridor,planMode,startMode,firstTrip,
            null,null,0,"latest");
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
