package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.JourneyLogicTests.*;
import static com.victorpena.just_in_transit.TransitModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DailyCommuteTests {
    private Schedule withFirst(String id,String board,String alight) {
        var s=schedule();var trips=new ArrayList<>(s.trips());
        trips.add(new Trip(id,"24","daily","Downtown",List.of(stop("81799",1,board),stop("76779",2,alight))));
        return new Schedule(s.stops(),trips,s.services(),s.exceptions());
    }
    @Test void lateFallbackIncludesWalkAndPreservesDeadline() {
        var f=new CommuteForm("Home","Work",DAY,LocalTime.of(8,35),8,3,10,3,5,5,"83463");
        var result=new CommutePlanner().plan(f,schedule(),LiveSnapshot.empty(),at("07:00"));
        assertTrue(result.lateFallback());assertEquals("8:40 AM",result.options().get(0).workDisplay());
        assertEquals("5 min late",result.options().get(0).deadlineLabel());
        assertTrue(result.message().contains("on your saved route"));
    }
    @Test void pastDeadlineStillFindsTodayRatherThanSilentlyPlanningTomorrow() {
        var f=new CommuteForm("Home","Work",DAY,LocalTime.of(7,0),8,3,10,3,5,5,"83463");
        var result=new CommutePlanner().plan(f,schedule(),LiveSnapshot.empty(),at("07:01"));
        assertTrue(result.lateFallback());assertEquals("100 min late",result.options().get(0).deadlineLabel());
    }
    @Test void recoveryOmitsHomeWalkAndNeverReoffersTheMissedBus() {
        var schedule=withFirst("a2","07:20","07:45");
        var state=ActiveJourney.at24(started(),at("07:16"));
        var recovered=ActiveJourney.missed24(state,schedule,LiveSnapshot.empty(),at("07:16"));
        assertEquals("a2",recovered.option().first().tripId());assertEquals("b",recovered.option().second().tripId());
        assertEquals(form(),recovered.form());assertEquals(ActiveJourney.Phase.WAITING_FOR_24,recovered.phase());
        assertFalse(recovered.needs24());
        var none=ActiveJourney.missed24(recovered,schedule,LiveSnapshot.empty(),at("07:17"));
        assertTrue(none.needs24());assertFalse(ActiveJourney.view(none,schedule,LiveSnapshot.empty(),at("07:17")).canAdvance());
        var retry=ActiveJourney.missed24(none,schedule,LiveSnapshot.empty(),at("07:18"));
        assertTrue(retry.needs24());
    }
    @Test void recoveryKeepsLateArrivalAndExcludesCanceledBuses() {
        var schedule=withFirst("a2","08:10","08:40");
        var state=ActiveJourney.at24(started(),at("07:16"));
        var recovered=ActiveJourney.missed24(state,schedule,LiveSnapshot.empty(),at("07:16"));
        assertEquals("40 min late",recovered.option().deadlineLabel());
        var canceled=ActiveJourney.missed24(state,schedule,live("a2","CANCELED",at("07:16")),at("07:16"));
        assertTrue(canceled.needs24());
        assertTrue(LiveMapData.countdowns(form(),canceled.option(),ActiveJourney.view(canceled,schedule,LiveSnapshot.empty(),at("07:16")),LiveSnapshot.empty(),at("07:16"))
            .stream().noneMatch(c->c.id().equals("bus24") || c.id().equals("bus93")));
    }
    @Test void missed24RequiresBeingAtTheStopAndCannotResetBoardedJourney() {
        assertThrows(IllegalArgumentException.class,()->ActiveJourney.missed24(started(),schedule(),LiveSnapshot.empty(),at("07:16")));
        assertThrows(IllegalArgumentException.class,()->ActiveJourney.missed24(waiting(),schedule(),LiveSnapshot.empty(),at("07:56")));
    }
    @Test void lateFallbackChoosesEarliestArrivalEvenIfLaterBusOvertakes() {
        var f=new CommuteForm("Home","Work",DAY,LocalTime.of(8,0),8,3,10,3,5,5,"83463");
        var s=schedule();var trips=new ArrayList<>(s.trips());
        trips.add(new Trip("express","93","daily","Six Flags",List.of(stop("76773",1,"08:00"),stop("83463",2,"08:20"))));
        var result=new CommutePlanner().plan(f,new Schedule(s.stops(),trips,s.services(),s.exceptions()),LiveSnapshot.empty(),at("07:00"));
        assertEquals("express",result.options().get(0).second().tripId());assertEquals("8:30 AM",result.options().get(0).workDisplay());
    }
    @Test void onTimeOptionAlwaysWinsOverLateOptions() {
        var result=new CommutePlanner().plan(form(),withFirst("a2","08:10","08:40"),LiveSnapshot.empty(),at("07:00"));
        assertFalse(result.lateFallback());assertTrue(result.options().stream().allMatch(o->o.arrivalMargin()>=0));
    }
    @Test void selectedConnectionThatBecomesImpossibleCannotBeStarted() {
        assertThrows(IllegalArgumentException.class,()->ActiveJourney.start(form(),selected(),schedule(),live("a","SCHEDULED",at("07:04"),delay(1,1200)),at("07:04")));
    }
}
