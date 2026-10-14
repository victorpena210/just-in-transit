package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import static com.victorpena.just_in_transit.LiveSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class JourneyLogicTests {
    static final LocalDate DAY=LocalDate.of(2026,10,12);
    static Instant at(String value) { return DAY.atTime(LocalTime.parse(value)).atZone(ZONE).toInstant(); }
    static StopTime stop(String id,int sequence,String at) { int seconds=LocalTime.parse(at).toSecondOfDay();return new StopTime(id,sequence,seconds,seconds,true,true); }
    static CommuteForm form() { return new CommuteForm("Home","Work",DAY,LocalTime.of(9,0),8,3,10,3,5,5,"83463"); }
    static Schedule schedule() {
        return new Schedule(Map.of(),List.of(
            new Trip("a","24","daily","Downtown",List.of(stop("81799",1,"07:15"),stop("76779",2,"07:45"))),
            new Trip("b","93","daily","Six Flags",List.of(stop("76773",1,"07:55"),stop("83463",2,"08:30"))),
            new Trip("c","93","daily","Six Flags",List.of(stop("76773",1,"08:55"),stop("83463",2,"09:30")))),
            Map.of("daily",new Service(DAY,DAY,EnumSet.allOf(DayOfWeek.class))),Map.of());
    }
    static Option selected() { return new CommutePlanner().plan(form(),schedule(),LiveSnapshot.empty(),at("07:00")).options().get(0); }
    static ActiveJourney.State started() { return ActiveJourney.start(form(),selected(),schedule(),LiveSnapshot.empty(),at("07:04")); }
    static ActiveJourney.State waiting() {
        var s=ActiveJourney.advance(started(),schedule(),LiveSnapshot.empty(),at("07:15"));
        s=ActiveJourney.advance(s,schedule(),LiveSnapshot.empty(),at("07:45"));
        return ActiveJourney.advance(s,schedule(),LiveSnapshot.empty(),at("07:48"));
    }
    static LiveSnapshot live(String trip,String relation,Instant now,StopPrediction... updates) {
        return new LiveSnapshot(now,Map.of(trip,List.of(new TripPrediction("20261012",relation,now,null,List.of(updates)))));
    }
    static StopPrediction delay(int sequence,int seconds) { return new StopPrediction(sequence,"","SCHEDULED",new Prediction(null,(long)seconds,null),new Prediction(null,(long)seconds,null)); }

    @Test void refreshAfterLeaveTimeKeepsChosenTrips() {
        assertTrue(new CommutePlanner().plan(form(),schedule(),LiveSnapshot.empty(),at("07:05")).options().isEmpty());
        var v=ActiveJourney.view(started(),schedule(),LiveSnapshot.empty(),at("07:05"));
        assertEquals("a",v.option().first().tripId());assertEquals("b",v.option().second().tripId());
        assertEquals("7:04 AM",v.option().leaveDisplay());
    }
    @Test void onlyFutureEventsRefreshAfterBoarding() {
        var s=ActiveJourney.advance(started(),schedule(),LiveSnapshot.empty(),at("07:16"));
        var v=ActiveJourney.view(s,schedule(),live("a","SCHEDULED",at("07:20"),delay(1,600)),at("07:20"));
        assertEquals("7:16 AM",v.option().first().board().display());assertEquals("7:55 AM",v.option().first().alight().display());
        assertTrue(v.warnings().stream().anyMatch(w->w.contains("earlier than")));
    }
    @Test void lateConnectionDoesNotSwapOrDropPinnedBus() {
        var v=ActiveJourney.view(started(),schedule(),live("a","SCHEDULED",at("07:05"),delay(1,1200)),at("07:05"));
        assertEquals("b",v.option().second().tripId());assertTrue(v.warnings().stream().anyMatch(w->w.contains("earlier than")));
    }
    @Test void missed93UsesRemainingJourneyAndShowsLateness() {
        var s=ActiveJourney.missed93(waiting(),schedule(),LiveSnapshot.empty(),at("07:56"));
        var v=ActiveJourney.view(s,schedule(),LiveSnapshot.empty(),at("07:56"));
        assertEquals("c",v.option().second().tripId());assertEquals("a",v.option().first().tripId());
        assertEquals("9:40 AM",v.option().workDisplay());assertEquals("40 min late",v.deadlineLabel());
        assertEquals(ActiveJourney.Phase.WAITING_FOR_93,s.phase());
    }
    @Test void noReplacementKeepsTransferAndDisablesBoarding() {
        var s=ActiveJourney.missed93(waiting(),schedule(),live("c","CANCELED",at("07:56")),at("07:56"));
        assertTrue(s.needs93());var v=ActiveJourney.view(s,schedule(),LiveSnapshot.empty(),at("07:56"));
        assertFalse(v.canAdvance());assertFalse(v.arrivalAvailable());assertEquals(ActiveJourney.Phase.WAITING_FOR_93,s.phase());
        s=ActiveJourney.missed93(s,schedule(),LiveSnapshot.empty(),at("08:00"));
        assertEquals("c",s.option().second().tripId());assertFalse(s.needs93());
    }
    @Test void missedActionRequiresArrivalAtTransferStop() {
        assertThrows(IllegalArgumentException.class,()->ActiveJourney.missed93(started(),schedule(),LiveSnapshot.empty(),at("07:10")));
    }
    @Test void finishedCommuteUsesConfirmedArrivalAndNeverReturnsToHomeSearch() {
        var s=ActiveJourney.advance(waiting(),schedule(),LiveSnapshot.empty(),at("07:55"));
        s=ActiveJourney.advance(s,schedule(),LiveSnapshot.empty(),at("08:32"));
        assertEquals("8:42 AM",ActiveJourney.view(s,schedule(),LiveSnapshot.empty(),at("08:33")).option().workDisplay());
        s=ActiveJourney.advance(s,schedule(),LiveSnapshot.empty(),at("08:43"));
        var v=ActiveJourney.view(s,null,LiveSnapshot.empty(),at("09:05"));
        assertTrue(v.finished());assertEquals("8:43 AM",v.option().workDisplay());assertEquals("17 min before your deadline",v.deadlineLabel());
        assertTrue(v.warnings().isEmpty());
    }
    @Test void canceledPinnedBusRemainsVisibleButCannotBeBoarded() {
        var v=ActiveJourney.view(waiting(),schedule(),live("b","CANCELED",at("07:50")),at("07:50"));
        assertEquals("b",v.option().second().tripId());assertFalse(v.canAdvance());assertFalse(v.arrivalAvailable());
    }
    @Test void missingScheduleRetainsJourneyWithUnconfirmedLabels() {
        var v=ActiveJourney.view(started(),null,LiveSnapshot.empty(),at("07:05"));
        assertEquals("a",v.option().first().tripId());assertFalse(v.option().first().board().usable());assertFalse(v.arrivalAvailable());
    }
    @Test void actualTransferArrivalReplacesEarlierPrediction() {
        var s=ActiveJourney.advance(started(),schedule(),LiveSnapshot.empty(),at("07:15"));
        s=ActiveJourney.advance(s,schedule(),LiveSnapshot.empty(),at("07:54"));
        var v=ActiveJourney.view(s,schedule(),LiveSnapshot.empty(),at("07:54"));
        assertEquals("7:54 AM",v.option().first().alight().display());assertEquals(-2L,v.option().transferMargin());
    }
    @Test void expiredAndForgedPlanSelectionsCannotStart() {
        var session=new JourneySession();var result=new Result(List.of(selected()),"fixture");
        String token=session.offer(form(),result,at("06:00"));
        assertThrows(IllegalArgumentException.class,()->session.start("wrong",0,schedule(),LiveSnapshot.empty(),at("06:01")));
        assertThrows(IllegalArgumentException.class,()->session.start(token,5,schedule(),LiveSnapshot.empty(),at("06:01")));
        assertThrows(IllegalArgumentException.class,()->session.start(token,0,schedule(),LiveSnapshot.empty(),at("07:04")));
    }
    @Test void staleProgressPostCannotAdvanceTwice() {
        var session=new JourneySession();String token=session.offer(form(),new Result(List.of(selected()),""),at("07:00"));
        session.start(token,0,schedule(),LiveSnapshot.empty(),at("07:04"));String revision=session.current(at("07:04")).revision();
        session.act(revision,"advance",schedule(),LiveSnapshot.empty(),at("07:15"));
        assertThrows(IllegalArgumentException.class,()->session.act(revision,"advance",schedule(),LiveSnapshot.empty(),at("07:16")));
        assertEquals(ActiveJourney.Phase.ON_24,session.current(at("07:16")).phase());
    }
    @Test void activeJourneyExpiresEvenIfPageKeepsRefreshing() {
        var session=new JourneySession();String token=session.offer(form(),new Result(List.of(selected()),""),at("07:00"));
        session.start(token,0,schedule(),LiveSnapshot.empty(),at("07:04"));
        assertNotNull(session.current(at("23:00")));assertNull(session.current(at("07:04").plus(Duration.ofHours(18))));
    }
    @Test void separateBrowserSessionsDoNotShareActiveJourneys() {
        var session=new JourneySession();String token=session.offer(form(),new Result(List.of(selected()),""),at("07:00"));
        session.start(token,0,schedule(),LiveSnapshot.empty(),at("07:04"));assertNull(new JourneySession().current(at("07:05")));
    }
    @Test void cannotStartTomorrowOrAfterWalkBecomesImpossible() {
        assertThrows(IllegalArgumentException.class,()->ActiveJourney.start(form(),selected(),schedule(),LiveSnapshot.empty(),at("07:08")));
        assertThrows(IllegalArgumentException.class,()->ActiveJourney.start(form(),selected(),schedule(),LiveSnapshot.empty(),at("07:04").minus(Duration.ofDays(1))));
    }
    @Test void startupGuardsUnprotectedNetworkAccessAndMissingPassword() {
        assertThrows(IllegalStateException.class,()->new AccessSettings(false,"0.0.0.0","victor",""));
        assertThrows(IllegalStateException.class,()->new AccessSettings(true,"127.0.0.1","victor",""));
        assertTrue(new AccessSettings(true,"0.0.0.0","victor","example-test-password").enabled());
    }
}
