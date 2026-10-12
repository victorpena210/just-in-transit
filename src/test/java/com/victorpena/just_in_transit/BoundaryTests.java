package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import static com.victorpena.just_in_transit.PlannerTests.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;

class BoundaryTests {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    @Test void backupIncludesFinalWalkAndReportsLatenessEvenWhenTooLateForPrimarySearch() {
        var base=schedule("07:55:00");var trips=new ArrayList<>(base.trips());
        trips.add(new Trip("later","93","weekday","Six Flags",List.of(stop("76773",1,"08:55:00"),stop("83463",2,"09:30:00"))));
        var s=new Schedule(base.stops(),trips,base.services(),base.exceptions());
        Backup backup=new CommutePlanner().plan(form(),s,RealtimeStore.Snapshot.empty(),NOW).options().get(0).backup();
        assertNotNull(backup);assertEquals("8:55 AM",backup.departureDisplay());assertEquals("9:40 AM",backup.workDisplay());
        assertFalse(backup.onTime());assertEquals("40 min late",backup.deadlineLabel());assertEquals(60,backup.minutesLater());
    }
    @Test void canceledBusIsNotUsedAsBackup() throws Exception {
        var base=schedule("07:55:00");var trips=new ArrayList<>(base.trips());
        trips.add(new Trip("later","93","weekday","Six Flags",List.of(stop("76773",1,"08:55:00"),stop("83463",2,"09:30:00"))));
        var live=RealtimeStore.Snapshot.parse(FeedMessage.newBuilder()
            .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(NOW.getEpochSecond()))
            .addEntity(FeedEntity.newBuilder().setId("cancel").setTripUpdate(TripUpdate.newBuilder()
                .setTrip(TripDescriptor.newBuilder().setTripId("later").setStartDate("20261012")
                    .setScheduleRelationship(TripDescriptor.ScheduleRelationship.CANCELED)))).build().toByteArray());
        var s=new Schedule(base.stops(),trips,base.services(),base.exceptions());
        assertNull(new CommutePlanner().plan(form(),s,live,NOW).options().get(0).backup());
    }
    @Test void selectedOriginActuallyChangesTheDeparture() {
        var base=schedule("07:55:00");var trip=base.trips().get(0);
        var alternate=new Trip("a","24","weekday","Downtown",List.of(stop("other",0,"07:05:00"),trip.stops().get(0),trip.stops().get(1)));
        var s=new Schedule(base.stops(),List.of(alternate,base.trips().get(1)),base.services(),base.exceptions());
        var f=new CommuteForm("Other home","Work",DAY,LocalTime.of(9,0),8,3,10,3,5,5,"83463","other","manual",null,null,null,null,null,null);
        assertEquals("6:54 AM",new CommutePlanner().plan(f,s,RealtimeStore.Snapshot.empty(),NOW).options().get(0).leaveDisplay());
    }
    @Test void nearbyStopsRespectDirectionPickupCalendarAndDistance() {
        Map<String,Stop> stops=Map.of("near",new Stop("near","Near",29.42,-98.45),"reverse",new Stop("reverse","Reverse",29.42001,-98.45),
            "far",new Stop("far","Far",29.7,-98.65),"noPickup",new Stop("noPickup","No pickup",29.421,-98.45));
        var trips=List.of(new Trip("a","24","weekday","Downtown",List.of(stop("near",1,"07:05:00"),
                new StopTime("noPickup",2,26000,26000,false,true),stop("far",3,"07:25:00"),stop("76779",4,"07:45:00"))),
            new Trip("reverse","24","weekday","Other direction",List.of(stop("76779",1,"07:00:00"),stop("reverse",2,"07:10:00"))));
        var s=new Schedule(stops,trips,schedule("07:55:00").services(),Map.of());
        assertEquals(List.of("near"),CorridorStops.nearby(s,"origin",DAY,29.42,-98.45).stream().map(CorridorStops.Nearby::id).toList());
        assertTrue(CorridorStops.nearby(s,"origin",DAY.plusDays(1),29.42,-98.45).isEmpty());
    }
    @Test void previousDayOvernightServiceStillOffersItsStops() {
        var s=new Schedule(Map.of("near",new Stop("near","Near",29.42,-98.45)),
            List.of(new Trip("night","24","weekday","Downtown",List.of(stop("near",1,"25:00:00"),stop("76779",2,"25:30:00")))),
            schedule("07:55:00").services(),Map.of());
        assertEquals(1,CorridorStops.nearby(s,"origin",DAY.plusDays(1),29.42,-98.45).size());
    }
    @Test void changedAddressCannotReuseResolvedLocation() {
        var f=new CommuteForm("Changed address","Work",DAY,LocalTime.of(9,0),8,3,10,3,5,5,"83463","81799","address",
            29.42,-98.45,29.6,-98.61,"Original address","Work");
        assertFalse(f.isLocationResolved());
    }
    @Test void walkingParserUsesPedestrianTimeAndRefusesMissingEstimate() throws Exception {
        var walk=MapService.parseWalk(bytes("{\"trip\":{\"status\":0,\"summary\":{\"time\":181,\"length\":0.21}}}"));
        assertEquals(4,walk.minutes());assertEquals(210,walk.meters());
        assertThrows(IOException.class,()->MapService.parseWalk(bytes("{\"trip\":{\"status\":0,\"summary\":{}}}")));
        assertThrows(IOException.class,()->MapService.parseWalk(bytes("{\"error\":\"No route\"}")));
    }
    @Test void geocoderRequiresLocalCoordinatesAndReturnsChoices() throws Exception {
        var places=MapService.parsePlaces(bytes("""
            {"features":[{"properties":{"housenumber":"123","street":"Example Street","city":"San Antonio"},"geometry":{"coordinates":[-98.45,29.42]}},
            {"properties":{"name":"Wrong city"},"geometry":{"coordinates":[13.38,52.5]}},
            {"properties":{"name":"Missing coordinates"},"geometry":{"coordinates":[]}}]}
            """));
        assertEquals(1,places.size());assertEquals("123 Example Street, San Antonio",places.get(0).label());
    }
    @Test void secondsOfLatenessAreNeverLabeledOnTime() {
        var leg=CommutePlanner.legs(schedule("07:55:00"),RealtimeStore.Snapshot.empty(),"93","76773","83463",DAY,NOW).get(0);
        var backup=new Backup(leg,NOW,10,-1);assertFalse(backup.onTime());assertEquals("1 min late",backup.deadlineLabel());
    }
}
