package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;
import java.util.*;

/** Dependency-free timing regression suite, also invoked by JUnit. */
public final class PersonalPlannerChecks {
    static final LocalDate DAY = LocalDate.of(2026,10,12);
    static final Instant NOW = at("06:00:00");
    static int checks;
    static Instant at(String time) { return DAY.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant(); }
    static StopTime stop(String id, int seq, String time) {
        int seconds = Arrays.stream(time.split(":")).mapToInt(Integer::parseInt).reduce(0,(a,b)->a*60+b);
        return new StopTime(id,seq,seconds,seconds,true,true);
    }
    static Trip a(String id, String board, String off) {
        return new Trip(id,"24","s","Downtown",List.of(stop("81799",1,board),stop("76779",2,off)));
    }
    static Trip b(String id, String board, String off) {
        return new Trip(id,"93","s","Six Flags",List.of(stop("76773",1,board),stop("83463",2,off)));
    }
    static Schedule schedule(Trip... trips) {
        return new Schedule(Map.of(),List.of(trips),Map.of("s",new Service(DAY,DAY,Set.of(DayOfWeek.MONDAY))),Map.of());
    }
    static Schedule connection(String transfer) { return schedule(a("a","07:15:00","07:45:00"),b("b",transfer,"08:30:00")); }
    static PersonalCommutePlanner.Result run(Schedule schedule, String deadline, int walk, Instant now, LiveSnapshot live) {
        return new PersonalCommutePlanner().plan(new PersonalCommutePlanner.Request(DAY,LocalTime.parse(deadline),walk),schedule,live,now);
    }
    static PersonalCommutePlanner.Plan plan(Schedule schedule) { return run(schedule,"09:00",0,NOW,LiveSnapshot.empty()).plan(); }
    static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    static LiveSnapshot update(String trip, String relationship, List<LiveSnapshot.StopPrediction> stops) {
        return new LiveSnapshot(NOW,Map.of(trip,List.of(new LiveSnapshot.TripPrediction("20261012",relationship,NOW,null,stops))));
    }
    public static void main(String[] args) { runChecks(); System.out.println("Passed " + checks + " personal commute checks."); }
    public static void runChecks() {
        checks=0;
        var base=plan(connection("07:53:00"));
        check(base!=null,"valid connection");
        check(base.leaveHome().equals(at("07:07:00")),"subtract five-minute walk and three-minute first-stop wait");
        check(base.firstStopDisplay().equals("7:12 AM"),"first stop arrival");
        check(base.transferSeconds()==480 && base.waitDisplay().equals("5 min"),"separate transfer walk and waiting");
        check(base.arrivalDisplay().equals("8:30 AM") && base.earlySeconds()==1800,"employee stop is default destination");
        check(plan(connection("07:58:00"))!=null,"exact ten-minute wait after the walk is allowed");
        check(plan(connection("07:58:01"))==null,"ten minutes waiting plus one second is rejected");
        check(plan(connection("07:51:00"))!=null,"exact six minutes allows walk and boarding cushion");
        check(plan(connection("07:50:59"))==null,"do not round unsafe transfer up");
        check(plan(connection("08:00:00"))==null,"no longer-wait fallback");
        check(plan(connection("07:44:00"))==null,"missed second bus rejected");
        check(run(connection("07:53:00"),"08:30",0,NOW,LiveSnapshot.empty()).plan()!=null,"exact deadline allowed");
        check(run(connection("07:53:00"),"08:29:59",0,NOW,LiveSnapshot.empty()).plan()==null,"one second late rejected");
        check(run(connection("07:53:00"),"08:39",10,NOW,LiveSnapshot.empty()).plan()==null,"final walk included in deadline");
        check(run(connection("07:53:00"),"08:40",10,NOW,LiveSnapshot.empty()).plan().arrival().equals(at("08:40:00")),"final walk exact deadline");
        var choices=schedule(a("early","07:00:00","07:30:00"),b("early93","07:36:00","08:05:00"),a("late","07:30:00","08:00:00"),b("late93","08:10:00","08:50:00"));
        check(plan(choices).first().tripId().equals("late"),"latest valid departure beats the shortest wait on an earlier trip");
        var pairs=schedule(a("a","07:15:00","07:45:00"),b("too-tight","07:49:00","08:15:00"),b("fits","07:54:00","08:31:00"));
        check(plan(pairs).second().tripId().equals("fits"),"all 93 pairings checked, not only the first bus");
        check(run(connection("07:53:00"),"09:00",0,at("07:07:00"),LiveSnapshot.empty()).plan()!=null,"can leave right now");
        check(run(connection("07:53:00"),"09:00",0,at("07:07:01"),LiveSnapshot.empty()).plan()==null,"departures requiring shortened home walk are excluded");
        check(run(connection("07:53:00"),"05:59",0,NOW,LiveSnapshot.empty()).message().contains("passed"),"expired deadline explained");
        check(run(null,"09:00",0,NOW,LiveSnapshot.empty()).message().contains("unavailable"),"schedule outage explained");
        check(run(connection("07:53:00"),"09:00",0,NOW,update("b","CANCELED",List.of())).plan()==null,"canceled 93 excluded");
        var skipped=new LiveSnapshot.StopPrediction(1,"76773","SKIPPED",null,null);
        check(run(connection("07:53:00"),"09:00",0,NOW,update("b","SCHEDULED",List.of(skipped))).plan()==null,"skipped boarding stop excluded");
        var delay=new LiveSnapshot.StopPrediction(2,"76779","SCHEDULED",new LiveSnapshot.Prediction(null,180L,null),null);
        check(run(connection("07:53:00"),"09:00",0,NOW,update("a","SCHEDULED",List.of(delay))).plan()==null,"delayed 24 breaks transfer buffer");
        var later=new LiveSnapshot.StopPrediction(1,"76773","SCHEDULED",null,new LiveSnapshot.Prediction(null,360L,null));
        check(run(connection("07:53:00"),"09:00",0,NOW,update("b","SCHEDULED",List.of(later))).plan()==null,"delayed 93 exceeds ten-minute waiting limit");
        var live=update("b","CANCELED",List.of());
        var stale=new LiveSnapshot(NOW.minusSeconds(121),live.trips());
        check(run(connection("07:53:00"),"09:00",0,NOW,stale).plan().dataLabel().equals("Scheduled times"),"stale data is honestly labeled as schedule fallback");
        var wrong=new Trip("wrong","24","s","Wrong way",List.of(stop("76779",1,"07:15:00"),stop("81799",2,"07:45:00")));
        check(plan(schedule(wrong,b("b","07:53:00","08:30:00")))==null,"opposite direction excluded");
        var noPickup=new Trip("no-pickup","24","s","Downtown",List.of(new StopTime("81799",1,26100,26100,false,true),stop("76779",2,"07:45:00")));
        check(plan(schedule(noPickup,b("b","07:53:00","08:30:00")))==null,"pickup restrictions respected");
        var sunday=new PersonalCommutePlanner.Request(DAY.plusDays(6),LocalTime.of(9,0),0);
        check(new PersonalCommutePlanner().plan(sunday,connection("07:53:00"),LiveSnapshot.empty(),NOW).plan()==null,"service calendar respected");
        var night=schedule(a("night24","23:20:00","23:50:00"),b("night93","23:58:00","24:35:00"));
        var midnight=new PersonalCommutePlanner().plan(new PersonalCommutePlanner.Request(DAY.plusDays(1),LocalTime.of(0,40),0),night,LiveSnapshot.empty(),at("22:00:00")).plan();
        check(midnight!=null && midnight.arrival().equals(DAY.plusDays(1).atTime(0,35).atZone(ZONE).toInstant()),"previous service day and GTFS times after midnight supported");
        check(midnight.leaveDayDisplay().startsWith("Mon"),"departure date is separate from arrival date");
        var nextDay=new PersonalCommutePlanner().plan(new PersonalCommutePlanner.Request(DAY.plusDays(1),LocalTime.of(9,0),0),connection("07:53:00"),LiveSnapshot.empty(),DAY.plusDays(1).atTime(6,0).atZone(ZONE).toInstant());
        check(nextDay.plan()==null && !nextDay.message().contains("already need"),"yesterday's daytime trips do not create a misleading missed-departure message");
        var request=new PersonalCommutePlanner.Request(DAY.minusDays(1),LocalTime.of(9,0),0);
        check(new PersonalCommutePlanner().plan(request,connection("07:53:00"),LiveSnapshot.empty(),NOW).plan()==null,"past date rejected");
    }
}
