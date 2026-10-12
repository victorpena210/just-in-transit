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
    @Pattern(regexp="manual|address") String locationMode,
    @DecimalMin("-90") @DecimalMax("90") Double originLat,
    @DecimalMin("-180") @DecimalMax("180") Double originLon,
    @DecimalMin("-90") @DecimalMax("90") Double destinationLat,
    @DecimalMin("-180") @DecimalMax("180") Double destinationLon,
    @Size(max=200) String resolvedOrigin,
    @Size(max=200) String resolvedDestination
) {
    public CommuteForm {
        if(originStop==null)originStop="81799";
        if(locationMode==null)locationMode="manual";
    }
    // Backward-compatible constructor for saved settings and callers of the original planner.
    public CommuteForm(String origin,String destination,LocalDate date,LocalTime arriveBy,
                       Integer walkToStop,Integer transferWalk,Integer walkToWork,Integer boardingBuffer,
                       Integer transferBuffer,Integer arrivalBuffer,String destinationStop) {
        this(origin,destination,date,arriveBy,walkToStop,transferWalk,walkToWork,boardingBuffer,
            transferBuffer,arrivalBuffer,destinationStop,"81799","manual",null,null,null,null,null,null);
    }
    @AssertTrue(message="Find and select both addresses again, or choose manual stops and walking times.")
    public boolean isLocationResolved() {
        return !"address".equals(locationMode) || originLat!=null && originLon!=null && destinationLat!=null
            && destinationLon!=null && java.util.Objects.equals(origin,resolvedOrigin)
            && java.util.Objects.equals(destination,resolvedDestination);
    }
}
