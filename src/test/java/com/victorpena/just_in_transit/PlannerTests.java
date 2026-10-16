package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PlannerTests {
    static final LocalDate DAY=LocalDate.of(2026,10,12);
    static final Instant NOW=DAY.atTime(6,0).atZone(ZONE).toInstant();
    static StopTime stop(String id,int sequence,String time) {
        int seconds=ScheduleStore.seconds(time);return new StopTime(id,sequence,seconds,seconds,true,true);
    }
    static Schedule schedule(String nextBus) {
        return new Schedule(Map.of(),List.of(
            new Trip("a","24","weekday","Downtown",List.of(stop("81799",1,"07:15:00"),stop("76779",2,"07:45:00"))),
            new Trip("b","93","weekday","Six Flags",List.of(stop("76773",1,nextBus),stop("83463",2,"08:30:00")))
        ),Map.of("weekday",new Service(DAY,DAY,Set.of(DayOfWeek.MONDAY))),Map.of());
    }
    static CommuteForm form() { return new CommuteForm("Home","Work",DAY,LocalTime.of(9,0),8,3,10,3,5,5,"83463").withArrivalWindow(5,30); }
    @Test void subtractsHomeWalkAndBufferAndIncludesFinalWalk() {
        var result=new CommutePlanner().plan(form(),schedule("07:55:00"),RealtimeStore.Snapshot.empty(),NOW);
        assertEquals(1,result.options().size());var option=result.options().get(0);
        assertEquals("7:04 AM",option.leaveDisplay());assertEquals("8:40 AM",option.workDisplay());
        assertEquals(7,option.transferMargin());assertEquals(20,option.arrivalMargin());
        assertTrue(option.meetsBuffers());assertEquals("Scheduled times",option.dataLabel());
    }
    @Test void impossibleWalkRejectsTransfer() {
        assertTrue(new CommutePlanner().plan(form(),schedule("07:47:00"),RealtimeStore.Snapshot.empty(),NOW).options().isEmpty());
    }
    @Test void barelyFeasibleTransferIsExplicitlyTight() {
        var option=new CommutePlanner().plan(form(),schedule("07:50:00"),RealtimeStore.Snapshot.empty(),NOW).options().get(0);
        assertFalse(option.meetsBuffers());assertEquals("Tight transfer",option.rating());assertEquals(2,option.transferMargin());
    }
    @Test void doesNotRecommendDeparturesAlreadyMissed() {
        var now=DAY.atTime(7,5).atZone(ZONE).toInstant();
        assertTrue(new CommutePlanner().plan(form(),schedule("07:55:00"),RealtimeStore.Snapshot.empty(),now).options().isEmpty());
    }
    @Test void reverseDirectionIsExcluded() {
        var s=schedule("07:55:00");var a=s.trips().get(0);
        var wrong=new Trip(a.id(),a.route(),a.service(),a.headsign(),List.of(a.stops().get(1),a.stops().get(0)));
        var reversed=new Schedule(s.stops(),List.of(wrong,s.trips().get(1)),s.services(),s.exceptions());
        assertTrue(new CommutePlanner().plan(form(),reversed,RealtimeStore.Snapshot.empty(),NOW).options().isEmpty());
    }
    @Test void previousServiceDaySupportsTimesAfterMidnight() {
        var s=new Schedule(Map.of(),List.of(new Trip("overnight","24","weekday","Downtown",List.of(stop("81799",1,"25:00:00"),stop("76779",2,"25:30:00")))),
            Map.of("weekday",new Service(DAY,DAY,Set.of(DayOfWeek.MONDAY))),Map.of());
        var legs=CommutePlanner.legs(s,RealtimeStore.Snapshot.empty(),"24","81799","76779",DAY.plusDays(1),NOW);
        assertEquals(1,legs.size());assertEquals(DAY.plusDays(1),legs.get(0).board().at().atZone(ZONE).toLocalDate());
        assertEquals("1:00 AM",legs.get(0).board().display());
    }
}
