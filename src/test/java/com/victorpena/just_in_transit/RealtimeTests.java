package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.victorpena.just_in_transit.PlannerTests.*;
import com.google.transit.realtime.GtfsRealtime.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class RealtimeTests {
    private TripUpdate.Builder update() {
        return TripUpdate.newBuilder().setTrip(TripDescriptor.newBuilder().setTripId("a").setStartDate("20261012"));
    }
    private TripUpdate.StopTimeUpdate.Builder prediction() {
        return TripUpdate.StopTimeUpdate.newBuilder().setStopId("81799").setStopSequence(1)
            .setArrival(TripUpdate.StopTimeEvent.newBuilder().setTime(serviceTime(DAY,ScheduleStore.seconds("07:19:00")).getEpochSecond()));
    }
    private RealtimeStore.Snapshot snapshot(TripUpdate.Builder trip,long age) throws IOException {
        return RealtimeStore.Snapshot.parse(FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(NOW.minusSeconds(age).getEpochSecond()))
            .addEntity(FeedEntity.newBuilder().setId("1").setTripUpdate(trip)).build().toByteArray());
    }
    private Event event(RealtimeStore.Snapshot live) {
        var a=schedule("07:55:00").trips().get(0);return live.event(a,DAY,a.stops().get(0),true,NOW);
    }
    @Test void arrivalOnlyIsLabeledBoardingProxy() throws Exception {
        var result=event(snapshot(update().addStopTimeUpdate(prediction()),0));
        assertTrue(result.live());assertEquals("7:19 AM",result.display());assertTrue(result.source().contains("proxy"));
    }
    @Test void staleFeedFallsBackToSchedule() throws Exception {
        var result=event(snapshot(update().addStopTimeUpdate(prediction()),121));
        assertFalse(result.live());assertEquals("7:15 AM",result.display());assertTrue(result.usable());
    }
    @Test void staleTripInFreshFeedAlsoFallsBack() throws Exception {
        assertFalse(event(snapshot(update().setTimestamp(NOW.minusSeconds(121).getEpochSecond()).addStopTimeUpdate(prediction()),0)).live());
    }
    @Test void canceledTripIsUnusable() throws Exception {
        var u=update();u.getTripBuilder().setScheduleRelationship(TripDescriptor.ScheduleRelationship.CANCELED);
        assertFalse(event(snapshot(u,0)).usable());
    }
    @Test void skippedStopIsUnusable() throws Exception {
        assertFalse(event(snapshot(update().addStopTimeUpdate(prediction().setScheduleRelationship(TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED)),0)).usable());
    }
    @Test void noDataIsNotPresentedAsLive() throws Exception {
        var result=event(snapshot(update().addStopTimeUpdate(prediction().setScheduleRelationship(TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA)),0));
        assertFalse(result.live());assertTrue(result.usable());assertEquals("7:15 AM",result.display());
    }
    @Test void differentServiceDateDoesNotApply() throws Exception {
        var u=update().addStopTimeUpdate(prediction());u.getTripBuilder().setStartDate("20261013");
        assertFalse(event(snapshot(u,0)).live());
    }
    @Test void delayOnlyDepartureIsAppliedToScheduledDeparture() throws Exception {
        var p=prediction().clearArrival().setDeparture(TripUpdate.StopTimeEvent.newBuilder().setDelay(120));
        var result=event(snapshot(update().addStopTimeUpdate(p),0));
        assertTrue(result.live());assertEquals("7:17 AM",result.display());assertFalse(result.source().contains("proxy"));
    }
    @Test void rejectsDifferentialFeedWithoutPretendingItIsComplete() {
        var bytes=FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setIncrementality(FeedHeader.Incrementality.DIFFERENTIAL)).build().toByteArray();
        assertThrows(IOException.class,()->RealtimeStore.Snapshot.parse(bytes));
    }
    @Test void protobufArrivalOnlyDelayReachesOmittedDownstreamStop() throws Exception {
        var live=snapshot(update().addStopTimeUpdate(prediction()),0);
        var a=schedule("07:55:00").trips().get(0);
        var downstream=live.event(a,DAY,a.stops().get(1),false,NOW);
        assertEquals("7:49 AM",downstream.display());assertTrue(downstream.live());
        assertTrue(downstream.source().contains("carried"));
    }
    @Test void protobufNoDataStopsDownstreamPropagation() throws Exception {
        var live=snapshot(update().addStopTimeUpdate(prediction()).addStopTimeUpdate(
            TripUpdate.StopTimeUpdate.newBuilder().setStopSequence(2)
                .setScheduleRelationship(TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA)),0);
        var a=schedule("07:55:00").trips().get(0);
        var downstream=live.event(a,DAY,a.stops().get(1),false,NOW);
        assertEquals("7:45 AM",downstream.display());assertFalse(downstream.live());
    }

}
