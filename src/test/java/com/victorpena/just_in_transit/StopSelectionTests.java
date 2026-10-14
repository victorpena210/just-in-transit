package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StopSelectionTests {
    private static final LocalDate DAY=LocalDate.of(2030,1,2);
    private static StopTime time(String id,int sequence,boolean pickup) {
        return new StopTime(id,sequence,25000+sequence*60,25000+sequence*60,pickup,true);
    }
    private static Schedule fixture() {
        Map<String,Stop> stops=new HashMap<>();var inbound=new ArrayList<StopTime>();
        for(int i=1;i<=6;i++) {
            String id="near"+i;stops.put(id,new Stop(id,"E. HOUSTON & CROSS STREET "+i,29.42+i*.0005,-98.454422));
            inbound.add(time(id,i,true));
        }
        stops.put("81799",new Stop("81799","E. HOUSTON & MITTMAN",29.425194,-98.454422));
        stops.put("reverse",new Stop("reverse","E. HOUSTON & MITTMAN",29.4251,-98.454422));
        stops.put("closed",new Stop("closed","E. HOUSTON & MITTMAN",29.4252,-98.454422));
        stops.put("edge",new Stop("edge","Boundary",29.42+Math.toDegrees(2000.2/6371000),-98.454422));
        stops.put("work",new Stop("work","FIESTA TEXAS EMPLOYEE ENTRANCE",29.59,-98.60));
        inbound.add(time("81799",7,true));inbound.add(time("closed",8,false));
        inbound.add(time("edge",9,true));inbound.add(time("76779",10,true));
        return new Schedule(stops,List.of(new Trip("in","24","daily","Downtown",inbound),
            new Trip("out","24","daily","East",List.of(time("76779",1,true),time("reverse",2,true))),
            new Trip("work","93","daily","Six Flags",List.of(time("76773",1,true),time("work",2,true)))),
            Map.of("daily",new Service(DAY,DAY,EnumSet.allOf(DayOfWeek.class))),Map.of());
    }
    @Test void intersectionSearchAcceptsPunctuationOrderAndStreetWords() {
        for(String query:List.of("Houston & Mittman","mittman","Mittman and Houston","E Houston at Mittman Street","81799"))
            assertEquals(List.of("81799"),CorridorStops.search(fixture(),"origin",DAY,query).stream().map(Stop::id).toList());
    }
    @Test void searchRespectsDateDirectionAndPickupPermissions() {
        assertTrue(CorridorStops.search(fixture(),"origin",DAY.plusDays(1),"Mittman").isEmpty());
        assertTrue(CorridorStops.search(fixture(),"destination",DAY,"Mittman").isEmpty());
        assertTrue(CorridorStops.search(fixture(),"origin",DAY,"reverse").isEmpty());
        assertTrue(CorridorStops.search(fixture(),"origin",DAY,"closed").isEmpty());
        assertEquals("work",CorridorStops.search(fixture(),"destination",DAY,"Fiesta Texas").get(0).id());
    }
    @Test void nearbyIncludesMittmanBeyondTheFirstFiveStops() {
        var stops=CorridorStops.nearby(fixture(),"origin",DAY,29.42,-98.454422);
        assertEquals(7,stops.size());assertEquals("81799",stops.get(6).id());
        for(int i=1;i<stops.size();i++)assertTrue(stops.get(i-1).straightLineMeters()<=stops.get(i).straightLineMeters());
    }
    @Test void distanceLimitUsesActualDistanceBeforeRounding() {
        assertFalse(CorridorStops.nearby(fixture(),"origin",DAY,29.42,-98.454422).stream().anyMatch(s->s.id().equals("edge")));
        assertEquals("edge",CorridorStops.search(fixture(),"origin",DAY,"Boundary").get(0).id());
    }
    @Test void stopSearchDoesNotNeedAnAddressOrGeocoder() {
        assertTrue(CorridorStops.nearby(fixture(),"origin",DAY,29.70,-98.70).isEmpty());
        assertEquals("81799",CorridorStops.search(fixture(),"origin",DAY,"Houston & Mittman").get(0).id());
    }
    @Test void emptyAndUnmatchedSearchesReturnNoStops() {
        for(String query:List.of("  ","&","and at street","Unknown intersection"))
            assertTrue(CorridorStops.search(fixture(),"origin",DAY,query).isEmpty());
        assertTrue(CorridorStops.search(null,"origin",DAY,"Mittman").isEmpty());
    }
    @Test void streetOnlyAndAreaGeocoderMatchesAreFlagged() throws Exception {
        var places=MapService.parsePlaces("""
            {"features":[
              {"properties":{"name":"Nolan Street","osm_key":"highway"},"geometry":{"coordinates":[-98.46,29.43]}},
              {"properties":{"name":"San Antonio","type":"city"},"geometry":{"coordinates":[-98.49,29.42]}},
              {"properties":{"street":"Nolan Street","city":"San Antonio"},"geometry":{"coordinates":[-98.46,29.43]}}
            ]}
            """.getBytes(StandardCharsets.UTF_8));
        assertEquals(3,places.size());assertTrue(places.stream().allMatch(MapService.Place::approximate));
    }
    @Test void numberedAddressesAndSpecificPlacesRemainSelectable() throws Exception {
        var places=MapService.parsePlaces("""
            {"features":[
              {"properties":{"housenumber":"123","street":"Example Street","city":"San Antonio"},"geometry":{"coordinates":[-98.46,29.43]}},
              {"properties":{"name":"Example Library","type":"house","osm_key":"amenity"},"geometry":{"coordinates":[-98.46,29.43]}}
            ]}
            """.getBytes(StandardCharsets.UTF_8));
        assertEquals(2,places.size());assertEquals("123",places.get(0).houseNumber());
        assertFalse(places.get(0).approximate());assertFalse(places.get(1).approximate());
    }
}
