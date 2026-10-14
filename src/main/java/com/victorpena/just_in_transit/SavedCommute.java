package com.victorpena.just_in_transit;

import jakarta.persistence.*;
import java.time.*;

@Entity
public class SavedCommute {
    @Id public Long id = 1L;
    @Column(length=200) public String origin;
    @Column(length=200) public String destination;
    public LocalTime arriveBy;
    public int walkToStop, transferWalk, walkToWork, boardingBuffer, transferBuffer, arrivalBuffer;
    public String destinationStop;
    public String originStop, locationMode;
    public String corridor,planMode;
    public Double originLat, originLon, destinationLat, destinationLon;
    @Column(length=200) public String resolvedOrigin;
    @Column(length=200) public String resolvedDestination;
    public SavedCommute() { }
    public static SavedCommute from(CommuteForm f) {
        var s = new SavedCommute();s.id=f.route().savedId();s.corridor=f.corridor();s.planMode=f.planMode();
        s.origin=f.origin(); s.destination=f.destination(); s.arriveBy=f.arriveBy();
        s.walkToStop=f.walkToStop(); s.transferWalk=f.transferWalk(); s.walkToWork=f.walkToWork();
        s.boardingBuffer=f.boardingBuffer(); s.transferBuffer=f.transferBuffer(); s.arrivalBuffer=f.arrivalBuffer();
        s.destinationStop=f.destinationStop();s.originStop=f.originStop();s.locationMode=f.locationMode();
        s.originLat=f.originLat();s.originLon=f.originLon();s.destinationLat=f.destinationLat();s.destinationLon=f.destinationLon();
        s.resolvedOrigin=f.resolvedOrigin();s.resolvedDestination=f.resolvedDestination();return s;
    }
    public CommuteForm form(LocalDate date) {
        return new CommuteForm(origin,destination,date,arriveBy,walkToStop,transferWalk,walkToWork,
                boardingBuffer,transferBuffer,arrivalBuffer,destinationStop,originStop,locationMode,
                originLat,originLon,destinationLat,destinationLon,resolvedOrigin,resolvedDestination,corridor,planMode,"home","");
    }
}
