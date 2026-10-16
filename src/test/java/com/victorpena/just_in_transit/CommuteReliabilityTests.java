package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.JourneyLogicTests.*;
import static com.victorpena.just_in_transit.TransitModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CommuteReliabilityTests {
    @Test void balancedPreferenceRanksShorterCompleteTripWhenBothFitWindows() {
        var base=schedule();var trips=new ArrayList<>(base.trips());
        trips.add(new Trip("later-first","24","daily","Downtown",List.of(stop("81799",1,"07:20"),stop("76779",2,"07:40"))));
        var result=new CommutePlanner().plan(form(),new Schedule(base.stops(),trips,base.services(),base.exceptions()),LiveSnapshot.empty(),at("07:00"));
        assertEquals("later-first",result.options().get(0).first().tripId());
        assertEquals(12,result.options().get(0).transferWait());
    }
    @Test void longWaitFallbackIsAvailableButNeverSaysBuffersMet() {
        var base=schedule();var trips=base.trips().stream().filter(t->!t.id().equals("b")).toList();
        var f=form().withTime(DAY,LocalTime.of(10,0),"arrive","home","");
        var result=new CommutePlanner().plan(f,new Schedule(base.stops(),trips,base.services(),base.exceptions()),LiveSnapshot.empty(),at("07:00"));
        assertEquals(1,result.options().size());assertFalse(result.options().get(0).meetsBuffers());
        assertEquals("Longer wait than preferred",result.options().get(0).displayRating());
    }
    @Test void actualFirstStopWaitHonorsBothEndsOfWindow() {
        var f=form().withJourney("stop","").withWaitLimits(10,20);
        var option=new CommutePlanner().plan(f,schedule(),LiveSnapshot.empty(),at("07:00")).options().get(0);
        assertEquals("Longer wait than preferred",option.rating());assertFalse(option.meetsBuffers());
        var tight=new CommutePlanner().plan(f,schedule(),LiveSnapshot.empty(),at("07:14")).options().get(0);
        assertEquals("Tight first boarding",tight.rating());
    }
    @Test void limitsSurviveSavedSetupAndDailyChanges() {
        var f=form().withWaitLimits(11,16);
        var restored=SavedCommute.from(f).form(DAY.plusDays(1)).withTime(DAY,LocalTime.of(10,0),"arrive","stop","");
        assertEquals(11,restored.maxBoardingWait());assertEquals(16,restored.maxTransferWait());
        var legacy=SavedCommute.from(form());legacy.maxBoardingWait=null;legacy.maxTransferWait=null;
        assertEquals(15,legacy.form(DAY).maxBoardingWait());assertEquals(30,legacy.form(DAY).maxTransferWait());
        assertFalse(form().withWaitLimits(1,2).isWaitWindowValid());
    }
    @Test void planningEarlyDoesNotPreventLaterRefreshOrStart() {
        var session=new JourneySession();String token=session.offer(form(),new Result(List.of(selected()),""),at("06:00"));
        assertNotNull(session.mapChoice(token,0,at("07:00")));
        session.start(token,0,schedule(),LiveSnapshot.empty(),at("07:04"));
        assertNotNull(session.current(at("07:04")));
    }
    @Test void oldPlanStillRevalidatesWhenStarting() {
        var session=new JourneySession();String token=session.offer(form(),new Result(List.of(selected()),""),at("06:00"));
        assertThrows(IllegalArgumentException.class,()->session.start(token,0,schedule(),LiveSnapshot.empty(),at("07:10")));
    }
    @Test void refreshedPreviewUsesTheNewWaitLimits() {
        var original=selected();var f=form().withWaitLimits(10,6);
        var preview=ActiveJourney.preview(f,original,schedule(),LiveSnapshot.empty(),at("07:00"));
        assertNotEquals("Buffers met",preview.rating());assertFalse(preview.meetsBuffers());
    }
}
