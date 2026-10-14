package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.JourneyLogicTests.*;
import static com.victorpena.just_in_transit.TransitModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CommuteWindowTests {
    private Schedule routes(Trip... extra) {
        var s=schedule();var all=new ArrayList<>(s.trips());all.addAll(List.of(extra));
        return new Schedule(s.stops(),all,s.services(),s.exceptions());
    }
    private Trip first(String id,String board,String off) { return new Trip(id,"24","daily","Downtown",List.of(stop("81799",1,board),stop("76779",2,off))); }
    private Trip second(String id,String board,String off) { return new Trip(id,"93","daily","Six Flags",List.of(stop("76773",1,board),stop("83463",2,off))); }
    @Test void walksAndWaitsAreSeparateInCompleteTrip() {
        var timing=selected().timing(form());
        assertEquals(3,timing.firstWait());assertEquals("7:12 AM",timing.atFirstStop());
        assertEquals(7,timing.transferWait());assertEquals("7:48 AM",timing.transferReady());assertEquals(96,timing.totalMinutes());
    }
    @Test void alreadyAtStopUsesActualWaitNotConfiguredMinimum() {
        var f=form().withJourney("stop","").withPreferences(10,30,0,"balanced");
        var o=new CommutePlanner().plan(f,schedule(),LiveSnapshot.empty(),at("07:00")).options().get(0);
        assertEquals(15,o.timing(f).firstWait());assertFalse(o.meetsBuffers());assertEquals("Longer wait than preferred",o.rating());
    }
    @Test void firstStopTooTightIsNotCalledBuffersMet() {
        var f=form().withJourney("stop","");
        var o=new CommutePlanner().plan(f,schedule(),LiveSnapshot.empty(),at("07:14")).options().get(0);
        assertFalse(o.meetsBuffers());assertEquals("Tight first boarding",o.rating());
    }
    @Test void trafficAllowanceChangesRecommendationWithoutFabricatingBusTimes() {
        var s=routes(second("roomier","08:05","08:40"));
        var f=form().withPreferences(15,30,5,"balanced");
        var result=new CommutePlanner().plan(f,s,LiveSnapshot.empty(),at("07:00"));
        assertEquals("roomier",result.options().get(0).second().tripId());
        assertEquals("8:50 AM",result.options().get(0).workDisplay());
        assertEquals("8:55 AM",result.options().get(0).timing(f).trafficArrival());
        assertTrue(result.options().get(0).meetsBuffers());
        assertEquals(5,SavedCommute.from(f).form(DAY).trafficAllowance());
    }
    @Test void longTransferCannotBeatOptionWithinRequestedWaitWindow() {
        var s=routes(first("early","06:40","07:10"));
        var f=form().withPreferences(15,20,0,"safer");
        var result=new CommutePlanner().plan(f,s,LiveSnapshot.empty(),at("06:00"));
        assertEquals("a",result.options().get(0).first().tripId());assertTrue(result.options().get(0).meetsBuffers());
        assertTrue(result.options().stream().anyMatch(o->o.first().tripId().equals("early") && !o.meetsBuffers()));
    }
    @Test void saferPreferenceCanChooseEarlierBusWithMoreTransferRoom() {
        var s=routes(first("earlier","07:05","07:35"));
        var f=form().withPreferences(15,30,0,"safer");
        assertEquals("earlier",new CommutePlanner().plan(f,s,LiveSnapshot.empty(),at("06:00")).options().get(0).first().tripId());
        assertEquals("a",new CommutePlanner().plan(f.withPreferences(15,30,0,"latest"),s,LiveSnapshot.empty(),at("06:00")).options().get(0).first().tripId());
    }
    @Test void oldSavedSettingsAcquireCompatibleDefaultsAndPreferencesSurviveStageChanges() {
        var old=SavedCommute.from(form());old.maxBoardingWait=null;old.maxTransferWait=null;old.trafficAllowance=null;old.preference=null;
        assertEquals(15,old.form(DAY).maxBoardingWait());assertEquals(0,old.form(DAY).trafficAllowance());
        var f=form().withPreferences(12,22,7,"safer").withJourney("stop","").withTime(DAY,LocalTime.NOON,"arrive","stop","");
        assertEquals(7,f.trafficAllowance());assertEquals(22,f.maxTransferWait());assertEquals("safer",f.preference());
    }
    @Test void staleDataAndPassedEstimatesNeverShowOnTrack() {
        var v=ActiveJourney.view(started(),schedule(),LiveSnapshot.empty(),at("07:05"));
        assertEquals("On schedule · unconfirmed",JourneyGuidance.from(v,schedule(),LiveSnapshot.empty(),at("07:05")).status());
        v=ActiveJourney.view(started(),schedule(),LiveSnapshot.empty(),at("07:20"));
        assertEquals("Confirm your progress",JourneyGuidance.from(v,schedule(),LiveSnapshot.empty(),at("07:20")).status());
    }
    @Test void riskyConnectionShowsAlternativeWhileOnFirstBusAndSwitchPreservesProgress() {
        var state=ActiveJourney.advance(started(),schedule(),LiveSnapshot.empty(),at("07:15"));
        var s=routes(second("replacement","08:10","08:45"));
        var now=at("07:20");var live=live("a","SCHEDULED",now,delay(2,600));
        var v=ActiveJourney.view(state,s,live,now);var advice=JourneyGuidance.from(v,s,live,now);
        assertEquals("Timing at risk",advice.status());assertTrue(advice.canReplace());assertTrue(advice.backup().contains("8:10 AM"));
        var switched=ActiveJourney.replaceSecond(state,s,live,now);
        assertEquals("replacement",switched.option().second().tripId());assertEquals(state.phase(),switched.phase());
        assertEquals(state.form(),switched.form());assertEquals(state.facts(),switched.facts());assertEquals("b",ActiveJourney.undo(switched,now).option().second().tripId());
    }
    @Test void livePreviewKeepsFirstStopShortfallVisible() {
        var f=form().withJourney("stop","");
        var preview=ActiveJourney.preview(f,selected(),schedule(),LiveSnapshot.empty(),at("07:14"));
        assertFalse(preview.meetsBuffers());assertEquals("Tight first boarding",preview.displayRating());
        assertEquals(1,preview.timing(f).firstWait());
    }
    @Test void changingTransferDoesNotRestartWalkingClock() {
        var state=ActiveJourney.advance(started(),schedule(),LiveSnapshot.empty(),at("07:15"));
        state=ActiveJourney.advance(state,schedule(),LiveSnapshot.empty(),at("07:45"));
        var switched=ActiveJourney.replaceSecond(state,schedule(),LiveSnapshot.empty(),at("07:46"));
        assertEquals(state.changed(),switched.changed());assertEquals(state.facts(),switched.facts());
    }

}
