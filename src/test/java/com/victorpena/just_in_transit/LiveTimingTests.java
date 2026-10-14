package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import static com.victorpena.just_in_transit.LiveSnapshot.*;
import static com.victorpena.just_in_transit.JourneyLogicTests.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LiveTimingTests {
    private Trip trip() { return new Trip("a","24","daily","Downtown",List.of(stop("one",1,"07:00"),stop("two",2,"07:10"),stop("three",3,"07:20"),stop("four",4,"07:30"))); }
    private Event event(LiveSnapshot snapshot,int stop) { var trip=trip();return snapshot.event(trip,DAY,trip.stops().get(stop-1),false,at("06:55")); }
    private LiveSnapshot snapshot(StopPrediction... updates) { return live("a","SCHEDULED",at("06:55"),updates); }
    @Test void delayCarriesThroughOmittedStops() {
        var e=event(snapshot(delay(1,300)),3);assertTrue(e.live());assertEquals("7:25 AM",e.display());assertTrue(e.source().contains("carried"));
    }
    @Test void absoluteTimeDerivesDelayFromCorrectScheduledEvent() {
        var departure=new StopPrediction(1,"","SCHEDULED",null,new Prediction(at("07:04"),null,null));
        assertEquals("7:24 AM",event(snapshot(departure),3).display());
    }
    @Test void laterPredictionReplacesPreviousDelay() {
        assertEquals("7:31 AM",event(snapshot(delay(1,300),delay(3,60)),4).display());
    }
    @Test void noDataStopsDelayUntilNewPrediction() {
        var noData=new StopPrediction(2,"","NO_DATA",null,null);
        assertFalse(event(snapshot(delay(1,300),noData),3).live());
        assertEquals("7:32 AM",event(snapshot(delay(1,300),noData,delay(3,120)),4).display());
    }
    @Test void skippedStopDoesNotBreakPropagationButCannotBeUsed() {
        var skipped=new StopPrediction(2,"","SKIPPED",null,null);
        assertFalse(event(snapshot(delay(1,300),skipped),2).usable());
        assertEquals("7:25 AM",event(snapshot(delay(1,300),skipped),3).display());
    }
    @Test void emptyScheduledUpdateResetsPreviousDelay() {
        assertFalse(event(snapshot(delay(1,300),new StopPrediction(2,"","SCHEDULED",null,null)),3).live());
    }
    @Test void staleTripAndDifferentServiceDateDoNotPropagate() {
        var old=new TripPrediction("20261012","SCHEDULED",at("06:52"),null,List.of(delay(1,300)));
        assertFalse(event(new LiveSnapshot(at("06:55"),Map.of("a",List.of(old))),3).live());
        var tomorrow=new TripPrediction("20261013","SCHEDULED",at("06:55"),null,List.of(delay(1,300)));
        assertFalse(event(new LiveSnapshot(at("06:55"),Map.of("a",List.of(tomorrow))),3).live());
    }
    @Test void tripDelayAppliesUntilStopPredictionOrNoData() {
        var update=new TripPrediction("20261012","SCHEDULED",at("06:55"),120L,List.of(delay(3,0)));
        var live=new LiveSnapshot(at("06:55"),Map.of("a",List.of(update)));
        assertEquals("7:12 AM",event(live,2).display());assertEquals("7:30 AM",event(live,4).display());
    }
    @Test void stopIdUpdatesResolveAgainstSchedule() {
        var update=new StopPrediction(null,"two","SCHEDULED",new Prediction(null,180L,null),null);
        assertEquals("7:23 AM",event(snapshot(update),3).display());assertFalse(event(snapshot(update),1).live());
    }
    @Test void uncertaintyAndBoardingProxyDoNotLookLikeDirectTransferPredictions() {
        var uncertain=new StopPrediction(1,"","SCHEDULED",new Prediction(null,0L,120),null);
        assertFalse(event(snapshot(uncertain),1).directPrediction());
        var e=snapshot(uncertain).event(trip(),DAY,trip().stops().get(0),true,at("06:55"));
        assertTrue(e.source().contains("proxy"));assertFalse(e.directPrediction());
    }
    @Test void sparseDelayCanInvalidatePreviouslyComfortableConnection() {
        var now=at("07:00");var live=live("a","SCHEDULED",now,delay(1,600));
        var result=new CommutePlanner().plan(form(),schedule(),live,now);
        assertTrue(result.lateFallback()); // 24 reaches transfer at 07:55; retain the late 08:55 recovery.
        assertEquals("c",result.options().get(0).second().tripId());assertEquals("40 min late",result.options().get(0).deadlineLabel());
    }
    @Test void scheduledConnectionShowsExplicitUncertainty() {
        var option=selected();assertFalse(option.connectionConfirmed());assertEquals("Buffers met · estimated",option.displayRating());
    }
    @Test void timingEventWithoutTimeOrDelayFallsBackAndResetsPropagation() {
        var empty=new StopPrediction(2,"","SCHEDULED",new Prediction(null,null,120),new Prediction(null,null,null));
        assertFalse(event(snapshot(delay(1,300),empty),2).live());
        assertFalse(event(snapshot(delay(1,300),empty),3).live());
    }

}
