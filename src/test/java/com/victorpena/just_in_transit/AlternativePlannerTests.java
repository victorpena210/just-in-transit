package com.victorpena.just_in_transit;
import static org.junit.jupiter.api.Assertions.*;import static com.victorpena.just_in_transit.TransitModels.*;import static com.victorpena.just_in_transit.JourneyLogicTests.*;
import java.util.*;import java.time.*;import org.junit.jupiter.api.Test;
class AlternativePlannerTests {
    Schedule network(int transferType,int minimum){
        var stops=new HashMap<String,Stop>();for(String id:List.of("a","x","y","b"))stops.put(id,new Stop(id,"Stop "+id,29.4,-98.5));
        return new Schedule(stops,List.of(new Trip("one","10","daily","East",List.of(stop("a",1,"07:15"),stop("x",2,"07:30"))),
            new Trip("two","20","daily","North",List.of(stop("y",1,"07:40"),stop("b",2,"08:00"))),
            new Trip("direct","30","daily","Direct",List.of(stop("a",1,"07:20"),stop("b",2,"08:15"))),
            new Trip("wrong","40","daily","Wrong direction",List.of(stop("b",1,"07:10"),stop("a",2,"07:50")))),
            Map.of("daily",new Service(DAY,DAY,EnumSet.allOf(DayOfWeek.class))),Map.of(),Map.of(),List.of(new Transfer("x","y",transferType,minimum,"","","","")));
    }
    List<AlternativePlanner.Choice> search(Schedule s){return new AlternativePlanner().search(s,LiveSnapshot.empty(),"a","b",at("07:00"),5,10,3,3,5,20);}
    @Test void searchesNewRoutesAndIncludesWalks(){var options=search(network(0,0));assertEquals("10 → 20",options.get(0).routes());assertEquals(at("08:10"),options.get(0).arrival());assertEquals(at("07:07"),options.get(0).leave());assertEquals(7,options.get(0).waitMinutes());assertTrue(options.stream().anyMatch(x->x.routes().equals("30")));assertTrue(options.stream().noneMatch(x->x.routes().contains("40")));}
    @Test void prohibitedAndTooShortTransfersAreExcluded(){assertTrue(search(network(3,0)).stream().allMatch(x->x.rides().size()==1));assertTrue(search(network(2,600)).stream().allMatch(x->x.rides().size()==1));}
    @Test void doesNotInventCrossStreetTransfers(){var s=network(0,0);var noTransfers=new Schedule(s.stops(),s.trips(),s.services(),s.exceptions());assertTrue(search(noTransfers).stream().allMatch(x->x.rides().size()==1));}
    @Test void arrivalSearchRanksLatestMatchingRouteAndLabelsEarlyFallback() {
        var f=new CommuteForm("Start","End",DAY,LocalTime.of(8,30),5,3,10,3,5,5,"b","a","manual",null,null,null,null,null,null).withArrivalWindow(5,15);
        var options=new AlternativePlanner().forCommute(network(0,0),LiveSnapshot.empty(),f,at("07:00"));
        assertEquals("30",options.get(0).routes());assertEquals(at("07:12"),options.get(0).leave());assertTrue(options.get(0).fits());
        var early=options.stream().filter(c->c.routes().equals("10 → 20")).findFirst().orElseThrow();assertFalse(early.fits());
        assertTrue(AlternativePlanner.preferenceNote(f,early).contains("5 min earlier than your limit"));
        assertTrue(new AlternativePlanner().forCommute(network(0,0),LiveSnapshot.empty(),f.withJourney("onboard","one"),at("07:00")).isEmpty());
    }
    @Test void rejectsCanceledTripsAndServiceOutsideCalendar(){var now=at("07:00");var live=live("one","CANCELED",now);assertTrue(new AlternativePlanner().search(network(0,0),live,"a","b",now,5,10,3,3,5,20).stream().allMatch(x->x.rides().size()==1));assertTrue(new AlternativePlanner().search(network(0,0),LiveSnapshot.empty(),"a","b",at("07:00").plusSeconds(86400),5,10,3,3,5,20).isEmpty());}
}
