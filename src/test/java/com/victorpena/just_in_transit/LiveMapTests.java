package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import static com.victorpena.just_in_transit.JourneyLogicTests.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.transit.realtime.GtfsRealtime.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LiveMapTests {
    private FeedEntity.Builder bus(String id,String trip,String date,Instant stamp) {
        var d=TripDescriptor.newBuilder().setTripId(trip).setRouteId("24");if(date!=null)d.setStartDate(date);
        var v=VehiclePosition.newBuilder().setTrip(d).setPosition(Position.newBuilder().setLatitude(29.425f).setLongitude(-98.454f));
        if(stamp!=null)v.setTimestamp(stamp.getEpochSecond());
        return FeedEntity.newBuilder().setId(id).setVehicle(v);
    }
    private VehicleStore.Snapshot feed(Instant now,FeedEntity.Builder... entities) throws Exception {
        var f=FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(now.getEpochSecond()));
        for(var e:entities)f.addEntity(e);return VehicleStore.Snapshot.parse(f.build().toByteArray());
    }
    @Test void reportsMatchTripAndServiceDateNotJustRouteNumber() throws Exception {
        var now=at("07:00");var f=feed(now,bus("selected","a","20261012",now),bus("tomorrow","a","20261013",now),bus("unknown-date","a",null,now));
        var buses=LiveMapData.buses(f,schedule(),"81799","83463",selected(),now);
        assertEquals(3,buses.size());assertEquals(1,buses.stream().filter(LiveMapData.Bus::selected).count());
        assertEquals("selected",buses.stream().filter(LiveMapData.Bus::selected).findFirst().orElseThrow().id());
    }
    @Test void staleMissingAndFutureVehicleTimestampsCannotBeLive() throws Exception {
        var now=at("07:00");var f=feed(now,bus("old","a","20261012",now.minusSeconds(91)),bus("unknown","a",null,null),bus("future","a",null,now.plusSeconds(31)));
        var buses=LiveMapData.buses(f,schedule(),"81799","83463",selected(),now);
        assertEquals(2,buses.size());assertTrue(buses.stream().noneMatch(LiveMapData.Bus::fresh));
        assertTrue(LiveMapData.buses(f,schedule(),"81799","83463",selected(),now.plusSeconds(601)).isEmpty());
    }
    @Test void freshVehicleInOldEnvelopeIsUnconfirmed() throws Exception {
        var now=at("07:00");var f=feed(now.minusSeconds(100),bus("a","a","20261012",now));
        assertFalse(LiveMapData.buses(f,schedule(),"81799","83463",selected(),now).get(0).fresh());
    }
    @Test void wrongDirectionUnknownTripsAndWrongRoutesAreNotDisplayed() throws Exception {
        var now=at("07:00");var base=schedule();var trips=new ArrayList<>(base.trips());
        trips.add(new Trip("reverse","24","daily","East",List.of(stop("76779",1,"07:00"),stop("81799",2,"07:20"))));
        var s=new Schedule(base.stops(),trips,base.services(),base.exceptions());
        var f=feed(now,bus("reverse","reverse","20261012",now),bus("unknown","absent","20261012",now),bus("mismatch","b","20261012",now));
        assertTrue(LiveMapData.buses(f,s,"81799","83463",selected(),now).isEmpty());
    }
    @Test void parserRejectsDifferentialAndIgnoresDeletedInvalidOrCanceledPositions() throws Exception {
        var now=at("07:00");var deleted=bus("deleted","a",null,now).setIsDeleted(true);
        var invalid=bus("invalid","a",null,now);invalid.getVehicleBuilder().getPositionBuilder().setLatitude(Float.NaN);
        var canceled=bus("canceled","a",null,now);canceled.getVehicleBuilder().getTripBuilder().setScheduleRelationship(TripDescriptor.ScheduleRelationship.CANCELED);
        assertTrue(feed(now,deleted,invalid,canceled).positions().isEmpty());
        var diff=FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setIncrementality(FeedHeader.Incrementality.DIFFERENTIAL));
        assertThrows(java.io.IOException.class,()->VehicleStore.Snapshot.parse(diff.build().toByteArray()));
    }
    @Test void leaveCountdownUsesWalkingAndBufferAndKeepsTheSameBuses() {
        var now=at("07:01");var live=live("a","SCHEDULED",now,delay(1,300));
        var option=ActiveJourney.preview(form(),selected(),schedule(),live,now);
        assertEquals("a",option.first().tripId());assertEquals("b",option.second().tripId());
        var clocks=LiveMapData.countdowns(form(),option,null,live,now);
        assertEquals(at("07:09"),clocks.get(0).target()); // 07:20 - 8-minute walk - 3-minute buffer
        assertEquals(4,clocks.size());assertTrue(clocks.get(0).source().contains("8 min walk + 3 min"));
    }
    @Test void canceledConnectionsDisableDepartureAndArrivalCountdowns() {
        var now=at("07:01");var live=live("b","CANCELED",at("07:01"));
        var option=ActiveJourney.preview(form(),selected(),schedule(),live,now);
        var clocks=LiveMapData.countdowns(form(),option,null,live,now);
        assertNull(clocks.get(0).target());assertNull(clocks.stream().filter(c->c.id().equals("bus93")).findFirst().orElseThrow().target());
        assertNull(clocks.get(3).target());
    }
    @Test void countdownExpiryRespectsTripTimestampEvenWithNewFeedEnvelope() {
        var now=at("07:01");var old=now.minusSeconds(110);
        var live=new LiveSnapshot(now,Map.of("a",List.of(new LiveSnapshot.TripPrediction("20261012","SCHEDULED",old,60L,List.of()))));
        var option=ActiveJourney.preview(form(),selected(),schedule(),live,now);
        assertEquals(now.plusSeconds(10),LiveMapData.countdowns(form(),option,null,live,now).get(0).validUntil());
    }
    @Test void boardedAndFinishedEventsDoNotKeepCountingDown() {
        var state=ActiveJourney.advance(started(),schedule(),LiveSnapshot.empty(),at("07:16"));
        var view=ActiveJourney.view(state,schedule(),LiveSnapshot.empty(),at("07:20"));
        var clocks=LiveMapData.countdowns(form(),view.option(),view,LiveSnapshot.empty(),at("07:20"));
        assertEquals(List.of("transfer","bus93","work"),clocks.stream().map(LiveMapData.Countdown::id).toList());
        state=ActiveJourney.advance(waiting(),schedule(),LiveSnapshot.empty(),at("07:55"));
        state=ActiveJourney.advance(state,schedule(),LiveSnapshot.empty(),at("08:30"));
        state=ActiveJourney.advance(state,schedule(),LiveSnapshot.empty(),at("08:40"));
        view=ActiveJourney.view(state,schedule(),LiveSnapshot.empty(),at("08:40"));
        assertTrue(LiveMapData.countdowns(form(),view.option(),view,LiveSnapshot.empty(),at("08:40")).isEmpty());
    }
    @Test void missingReplacementDoesNotDisplayTheMissed93Countdown() {
        var state=ActiveJourney.missed93(waiting(),schedule(),live("c","CANCELED",at("07:56")),at("07:56"));
        var view=ActiveJourney.view(state,schedule(),LiveSnapshot.empty(),at("07:56"));
        var clocks=LiveMapData.countdowns(form(),view.option(),view,LiveSnapshot.empty(),at("07:56"));
        assertEquals(1,clocks.size());assertEquals("work",clocks.get(0).id());assertNull(clocks.get(0).target());
    }
    @Test void previewTokensExpireAndDoNotLeakAcrossSessions() {
        var session=new JourneySession();var token=session.offer(form(),new Result(List.of(selected()),"Test"),at("07:00"));
        assertNotNull(session.mapChoice(token,0,at("07:01")));assertNull(session.mapChoice(token,-1,at("07:01")));
        assertNull(session.mapChoice(token,0,at("07:21")));assertNull(new JourneySession().mapChoice(token,0,at("07:01")));
    }
}
