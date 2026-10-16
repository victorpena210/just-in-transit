package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.JourneyLogicTests.*;
import static com.victorpena.just_in_transit.TransitModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ArrivalWindowTests {
    private CommuteForm afternoon() { return new CommuteForm("Home","Work",DAY,LocalTime.of(16,0),8,3,10,3,5,5,"83463").withArrivalWindow(5,15); }
    private Trip trip(String id,String route,String from,String to,String board,String off) {
        return new Trip(id,route,"daily","Destination",List.of(stop(from,1,board),stop(to,2,off)));
    }
    private Schedule afternoonSchedule() {
        var base=schedule();
        return new Schedule(base.stops(),List.of(
            trip("early24","24","81799","76779","12:39","12:56"),trip("early93","93","76773","83463","13:07","13:40"),
            trip("room24","24","81799","76779","14:25","14:55"),trip("room93","93","76773","83463","15:10","15:40"),
            trip("latest24","24","81799","76779","14:40","15:10"),trip("latest93","93","76773","83463","15:20","15:45")),base.services(),base.exceptions());
    }
    @Test void latestDepartureWinsOverShorterTripThatArrives130MinutesEarly() {
        var f=afternoon();var result=new CommutePlanner().plan(f,afternoonSchedule(),LiveSnapshot.empty(),at("10:00"));
        var best=result.options().get(0);
        assertEquals("latest24",best.first().tripId());assertEquals("2:29 PM",best.leaveDisplay());assertEquals("3:55 PM",best.workDisplay());
        assertTrue(best.meetsBuffers());assertTrue(result.message().contains("Latest departure"));
        assertEquals("latest24",new CommutePlanner().plan(f.withPreferences(15,30,0,"balanced"),afternoonSchedule(),LiveSnapshot.empty(),at("10:00")).options().get(0).first().tripId());
    }
    @Test void saferChoiceStaysInsideArrivalWindow() {
        var result=new CommutePlanner().plan(afternoon().withPreferences(15,30,0,"safer"),afternoonSchedule(),LiveSnapshot.empty(),at("10:00"));
        assertEquals("room24",result.options().get(0).first().tripId());assertTrue(result.options().get(0).meetsBuffers());
    }
    @Test void earlyOnlyFallbackIsExplicitAndRetainsTheUsersLimit() {
        var all=afternoonSchedule();var early=new Schedule(all.stops(),all.trips().subList(0,2),all.services(),all.exceptions());var f=afternoon();
        var result=new CommutePlanner().plan(f,early,LiveSnapshot.empty(),at("10:00"));var best=result.options().get(0);
        assertEquals(130,best.arrivalMargin());assertFalse(best.meetsBuffers());assertEquals("Earlier than your limit",best.rating());
        assertTrue(result.message().contains("No trip fits all your preferences"));assertTrue(best.comparisonLabel(best).contains("outside preferences"));
        assertTrue(best.timing(f).preferenceNote().contains("115 min earlier than your limit"));assertEquals(15,f.maxEarlyArrival());
    }
    @Test void secondsAndTrafficAllowanceAreEnforcedAtBothWindowEdges() {
        var f=afternoon().withPreferences(15,30,5,"latest");
        assertTrue(CommuteTiming.meets(f,180,600,900));assertTrue(CommuteTiming.meets(f,180,600,600));
        assertFalse(CommuteTiming.meets(f,180,600,901));assertFalse(CommuteTiming.meets(f,180,600,599));
        assertFalse(f.withArrivalWindow(5,9).isArrivalWindowValid());
    }
    @Test void savedAndRecoveredFormsKeepWindowWhileOldSavesGainLatestDefault() {
        var f=afternoon().withArrivalWindow(6,18).withPreferences(10,12,2,"safer");
        var restored=SavedCommute.from(f).form(DAY.plusDays(1)).withJourney("stop","").withTime(DAY,LocalTime.NOON,"arrive","stop","");
        assertEquals(18,restored.maxEarlyArrival());assertEquals(6,restored.arrivalBuffer());assertEquals("safer",restored.preference());
        var json=tools.jackson.databind.json.JsonMapper.builder().build();assertEquals(f,json.readValue(json.writeValueAsString(f),CommuteForm.class));
        var old=SavedCommute.from(f);old.maxEarlyArrival=null;old.preference="balanced";
        assertEquals(15,old.form(DAY).maxEarlyArrival());assertEquals("latest",old.form(DAY).preference());
    }
    @Test void refreshRetainsTooEarlyWarningAndLeaveAfterIgnoresArrivalWindow() {
        var f=afternoon();var s=afternoonSchedule();var early=new CommutePlanner().plan(f,s,LiveSnapshot.empty(),at("10:00")).options().stream().filter(o->o.first().tripId().equals("early24")).findFirst().orElseThrow();
        var preview=ActiveJourney.preview(f,early,s,LiveSnapshot.empty(),at("10:01"));
        assertFalse(preview.meetsBuffers());assertEquals("Earlier than your limit",preview.rating());
        var depart=f.withTime(DAY,LocalTime.of(12,0),"depart","home","").withArrivalWindow(60,0);
        assertTrue(depart.isArrivalWindowValid());assertTrue(new CommutePlanner().plan(depart,s,LiveSnapshot.empty(),at("10:00")).options().get(0).meetsBuffers());
    }
}
