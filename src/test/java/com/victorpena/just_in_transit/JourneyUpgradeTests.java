package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import static com.victorpena.just_in_transit.JourneyLogicTests.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JourneyUpgradeTests {
    static CommuteForm homeForm() {
        return new CommuteForm("Work","Home",DAY,LocalTime.of(17,0),15,3,8,3,5,0,"81793","75229","manual",null,null,null,null,null,null,"home","depart","home","");
    }
    static Schedule returnSchedule() {
        var stops=new HashMap<>(schedule().stops());
        for(var id:List.of("75229","76779","76773","81793","81799","83463"))stops.put(id,new Stop(id,"Stop "+id,29.43,-98.50));
        return new Schedule(stops,List.of(
            new Trip("return93","93","daily","Downtown",List.of(stop("75229",1,"17:20"),stop("76779",2,"18:00"))),
            new Trip("return24","24","daily","East side",List.of(stop("76773",1,"18:10"),stop("81793",2,"18:35"))),
            new Trip("later24","24","daily","East side",List.of(stop("76773",1,"18:40"),stop("81793",2,"19:05"))),
            new Trip("wrong93","93","daily","Six Flags",List.of(stop("76779",1,"17:20"),stop("75229",2,"18:00"))),
            new Trip("wrong24","24","daily","Downtown",List.of(stop("81793",1,"18:10"),stop("76773",2,"18:35")))) ,
            schedule().services(),Map.of());
    }
    @Test void atStopStartWorksAfterLeaveTimeWithoutRecordingAnInventedDeparture() {
        var f=form().withJourney("stop","");
        assertTrue(new CommutePlanner().plan(form(),schedule(),LiveSnapshot.empty(),at("07:10")).options().isEmpty());
        var option=new CommutePlanner().plan(f,schedule(),LiveSnapshot.empty(),at("07:10")).options().get(0);
        var state=ActiveJourney.start(f,option,schedule(),LiveSnapshot.empty(),at("07:10"),true);
        assertEquals(ActiveJourney.Phase.WAITING_FOR_24,state.phase());assertNull(state.facts().plannedDeparture());
        assertFalse(state.facts().confirmations().containsKey("leftOrigin"));assertTrue(state.facts().keepHistory());
        assertEquals("bus24",LiveMapData.countdowns(f,option,null,LiveSnapshot.empty(),at("07:10")).get(0).id());
    }
    @Test void aboardStartSelectsActualTripAndOnlyCountsDownRemainingJourney() {
        var f=form().withJourney("onboard","a@"+DAY);var planner=new CommutePlanner();
        assertEquals("a@"+DAY,planner.boardingChoices(f,schedule(),LiveSnapshot.empty(),at("07:20")).get(0).key());
        var option=planner.plan(f,schedule(),LiveSnapshot.empty(),at("07:20")).options().get(0);
        var state=ActiveJourney.start(f,option,schedule(),LiveSnapshot.empty(),at("07:20"));
        assertEquals(ActiveJourney.Phase.ON_24,state.phase());assertFalse(state.facts().confirmations().containsKey("ON_24"));
        assertFalse(state.option().first().board().source().contains("You confirmed"));
        var clocks=LiveMapData.countdowns(f,option,null,LiveSnapshot.empty(),at("07:20"));
        assertEquals("transfer",clocks.get(0).id());assertTrue(clocks.stream().noneMatch(c->Set.of("leave","bus24").contains(c.id())));
        assertTrue(planner.plan(f.withJourney("onboard","wrong"),schedule(),LiveSnapshot.empty(),at("07:20")).options().isEmpty());
        assertThrows(IllegalArgumentException.class,()->ActiveJourney.start(f,option,schedule(),LiveSnapshot.empty(),at("07:46")));
    }
    @Test void undoRestoresConfirmedTimesAndSurvivesCheckpointSerialization() {
        var original=started();var boarded=ActiveJourney.advance(original,schedule(),LiveSnapshot.empty(),at("07:16"));
        var mapper=JsonMapper.builder().build();var restored=mapper.readValue(mapper.writeValueAsString(boarded),ActiveJourney.State.class);
        var undone=ActiveJourney.undo(restored,at("07:17"));
        assertEquals(original.option(),undone.option());assertEquals(original.facts(),undone.facts());assertEquals(original.changed(),undone.changed());
        assertEquals(original.started(),undone.started());assertNull(undone.previous());assertNotEquals(original.revision(),undone.revision());
        assertThrows(IllegalArgumentException.class,()->ActiveJourney.undo(undone,at("07:18")));
    }
    @Test void undoMissedConnectionRestoresTheSelectedBusAndMissedSet() {
        var before=waiting();var missed=ActiveJourney.missed93(before,schedule(),LiveSnapshot.empty(),at("07:56"));
        assertEquals("c",missed.option().second().tripId());
        var undo=ActiveJourney.undo(missed,at("07:57"));assertEquals("b",undo.option().second().tripId());
        assertEquals(before.missed(),undo.missed());assertFalse(undo.facts().confirmations().containsKey("missedTransfer"));
        assertEquals(before.changed(),undo.changed());
    }
    @Test void goingHomeUsesReverseStopOrderAndLeaveAfterInsteadOfArrivalDeadline() {
        var f=homeForm();var s=returnSchedule();var result=new CommutePlanner().plan(f,s,LiveSnapshot.empty(),at("16:50"));
        assertFalse(result.options().isEmpty());var o=result.options().get(0);
        assertEquals("return93",o.first().tripId());assertEquals("return24",o.second().tripId());
        assertEquals(at("17:02"),o.leaveHome());assertEquals("6:43 PM",o.workDisplay());assertFalse(o.hasDeadline());
        assertEquals("No arrival deadline set",o.deadlineLabel());assertEquals("No arrival deadline set",o.backup().deadlineLabel());
        assertFalse(result.lateFallback());
        assertEquals(List.of("75229"),CorridorStops.eligible(s,"origin",DAY,f.route()).stream().map(Stop::id).toList());
        assertEquals(List.of("81793"),CorridorStops.eligible(s,"destination",DAY,f.route()).stream().map(Stop::id).toList());
        var state=ActiveJourney.start(f,o,s,LiveSnapshot.empty(),at("17:02"));
        assertEquals("Walking to the 93",ActiveJourney.view(state,s,LiveSnapshot.empty(),at("17:02")).phaseLabel());
        assertEquals("93 boarding in",LiveMapData.countdowns(f,o,null,LiveSnapshot.empty(),at("17:02")).get(1).label());
        for(String time:List.of("17:20","18:00","18:03"))state=ActiveJourney.advance(state,s,LiveSnapshot.empty(),at(time));
        var replacement=ActiveJourney.missed93(state,s,LiveSnapshot.empty(),at("18:11"));
        assertEquals("later24",replacement.option().second().tripId());assertEquals("Waiting for the 24",ActiveJourney.view(replacement,s,LiveSnapshot.empty(),at("18:11")).phaseLabel());
    }
    @Test void stageAtStopOverridesFutureLeaveAfterTime() {
        var f=homeForm().withTime(DAY,LocalTime.of(19,0),"depart","stop","");
        var o=new CommutePlanner().plan(f,returnSchedule(),LiveSnapshot.empty(),at("17:15")).options().get(0);
        assertEquals("return93",o.first().tripId());
    }
    @Test void reportsAreOptInAndOnlyCreatedWhenFinishedWithConfirmedArrival() {
        var repo=mock(TripReportRepository.class);var history=new TripHistory(repo);
        history.complete(started());verifyNoInteractions(repo);
        var f=form();var state=ActiveJourney.start(f,selected(),schedule(),LiveSnapshot.empty(),at("07:04"),true);
        history.complete(state);verifyNoInteractions(repo);
        for(String time:List.of("07:15","07:45","07:48","07:55","08:30","08:41"))state=ActiveJourney.advance(state,schedule(),LiveSnapshot.empty(),at(time));
        history.complete(state);
        var capture=org.mockito.ArgumentCaptor.forClass(TripReport.class);verify(repo).save(capture.capture());
        var report=capture.getValue();assertEquals(at("07:04"),report.actualDeparture);assertEquals(at("08:41"),report.actualArrival);
        assertEquals(at("08:40"),report.plannedArrival);assertEquals("Made selected transfer (confirmed)",report.transferOutcome);
        assertEquals("1 min later than planned",report.arrivalDifference());
    }
}
