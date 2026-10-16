package com.victorpena.just_in_transit;
import static com.victorpena.just_in_transit.TransitModels.*;
import static com.victorpena.just_in_transit.JourneyLogicTests.*;
import java.time.*;import java.util.*;
import org.springframework.boot.SpringApplication;import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
/** Opt-in local browser QA: synthetic transit, fixture clock, in-memory database. Never packaged in the app. */
public class BrowserHarness {
    public static void main(String[] args){var app=new SpringApplication(JustInTransitApplication.class,Fixtures.class);app.run(args);}
    @TestConfiguration static class Fixtures {
        @Bean @Primary Clock fixtureClock(){return Clock.offset(Clock.system(ZONE),Duration.between(Instant.now(),at("07:00")));}
        @Bean @Primary ScheduleStore fixtureSchedule(){return new ScheduleStore("","/tmp/jit-browser-fixture",false){@Override public Schedule get(){var base=schedule();var stops=new HashMap<String,Stop>();for(String id:List.of("81799","76779","76773","83463","75229","81793"))stops.put(id,new Stop(id,"Public stop "+id,29.43,-98.50));var trips=new ArrayList<>(base.trips());trips.addAll(JourneyUpgradeTests.returnSchedule().trips());trips.add(new Trip("downtown-loop","93","daily","Downtown loop",List.of(stop("76773",1,"07:05"))));trips.add(new Trip("direct","94","daily","Direct alternative",List.of(stop("81799",1,"07:15"),stop("83463",2,"08:20"))));return new Schedule(stops,trips,base.services(),base.exceptions(),Map.of(),List.of(new Transfer("76779","76773",0,180,"","","","")));}@Override public String message(){return "Synthetic schedule for browser checks";}};}
        @Bean @Primary VehicleStore fixtureVehicles(){return new VehicleStore("",false){@Override public Snapshot get(){return new Snapshot(at("07:00"),List.of(new Position("9301","downtown-loop","93","20261012",29.43,-98.50,at("07:00")),new Position("9302","b","93","20261012",29.431,-98.501,at("07:00"))));}@Override public String message(){return "Synthetic vehicle feed";}};}
        @Bean @Primary JevClient fixtureJev(){return new JevClient("","test-model","off",0){@Override public boolean available(){return true;}@Override public Evaluation compare(Map<String,Object> state,Set<String> ids){String id=ids.stream().sorted().findFirst().orElseThrow();return new Evaluation("local-comparison-fixture",List.of(new Judgment("Suggested departure",id,0.8,Map.of(id,0.8,"uncertain",0.2))));}@Override public Evaluation evaluate(Map<String,Object> state,int count,boolean hasNote){return new Evaluation("local-test-fixture",List.of(new Judgment("Suggested interface action","refresh_estimates",0.8,Map.of("refresh_estimates",0.8,"uncertain",0.2))));}};}
    }
}
