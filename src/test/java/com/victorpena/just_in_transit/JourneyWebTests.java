package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.JourneyLogicTests.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties={"via.enabled=false","maps.enabled=false","spring.datasource.url=jdbc:h2:mem:journey-web;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class JourneyWebTests {
    @Autowired MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean BrowserIdentity browser;
    @Autowired BrowserCommuteStore saved;
    @Autowired TripReportRepository reports;
    @MockitoBean ScheduleStore schedules;
    @MockitoBean Clock clock;
    @BeforeEach void setup() { org.mockito.Mockito.when(browser.id()).thenReturn("test-browser");
        saved.deleteAll();reports.deleteAll();var base=schedule();
        var stops=java.util.Map.of("81799",new TransitModels.Stop("81799","Houston & Mittman",29.425,-98.454),
            "76779",new TransitModels.Stop("76779","Martin & Leona",29.43,-98.502),
            "76773",new TransitModels.Stop("76773","Martin Opposite Leona",29.43,-98.502),
            "83463",new TransitModels.Stop("83463","Employee entrance",29.59,-98.60));
        when(schedules.get()).thenReturn(new TransitModels.Schedule(stops,base.trips(),base.services(),base.exceptions()));
        when(clock.instant()).thenReturn(at("07:00"));when(clock.withZone(any())).thenReturn(Clock.fixed(at("07:00"),TransitModels.ZONE));
    }
    private MockHttpServletRequestBuilder plan() {
        return post("/plan").with(csrf()).param("origin","Test home").param("destination","Work")
            .param("date","2026-10-12").param("arriveBy","09:00").param("walkToStop","8").param("transferWalk","3")
            .param("walkToWork","10").param("boardingBuffer","3").param("transferBuffer","5").param("arrivalBuffer","5")
            .param("originStop","81799").param("destinationStop","83463");
    }
    private MockHttpSession start() throws Exception {
        var response=mvc.perform(plan()).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)response.getRequest().getSession(false);
        String token=(String)response.getModelAndView().getModel().get("planToken");
        when(clock.instant()).thenReturn(at("07:04"));
        mvc.perform(post("/journey/start").session(session).with(csrf()).param("token",token).param("option","0"))
            .andExpect(redirectedUrl("/"));return session;
    }
    @Test void refreshAndReplanningKeepActiveCommuteWithoutSavingAddresses() throws Exception {
        var session=start();when(clock.instant()).thenReturn(at("07:05"));
        mvc.perform(get("/").session(session)).andExpect(status().isOk()).andExpect(content().string(containsString("Walking to the 24")))
            .andExpect(content().string(containsString("7:15 AM"))).andExpect(content().string(not(containsString("id=\"commute-form\""))));
        mvc.perform(plan().session(session)).andExpect(redirectedUrl("/"));
        assertEquals(0,saved.count());
    }
    @Test void progressUsesPrgAndRejectsDuplicateSubmission() throws Exception {
        var session=start();var result=mvc.perform(get("/").session(session)).andReturn();
        var view=(ActiveJourney.View)result.getModelAndView().getModel().get("journey");String revision=view.state().revision();
        when(clock.instant()).thenReturn(at("07:15"));
        mvc.perform(post("/journey/action").session(session).with(csrf()).param("revision",revision).param("action","advance")).andExpect(redirectedUrl("/"));
        mvc.perform(post("/journey/action").session(session).with(csrf()).param("revision",revision).param("action","advance"))
            .andExpect(flash().attribute("notice",containsString("another tab")));
        mvc.perform(get("/").session(session)).andExpect(content().string(containsString("On the 24")));
    }
    @Test void startAndProgressAreCsrfProtected() throws Exception {
        mvc.perform(post("/journey/start").param("token","bad").param("option","0")).andExpect(status().isForbidden());
        mvc.perform(post("/journey/action").param("revision","bad").param("action","end")).andExpect(status().isForbidden());
    }
    @Test void badSelectionReturnsReadableNoticeInsteadOfServerError() throws Exception {
        mvc.perform(post("/journey/start").with(csrf()).param("token","bad").param("option","-1"))
            .andExpect(redirectedUrl("/")).andExpect(flash().attribute("notice",containsString("expired")));
    }
    @Test void mapPreviewIsSessionBoundAndDoesNotStartOrSaveTheCommute() throws Exception {
        var response=mvc.perform(plan()).andReturn();var session=(MockHttpSession)response.getRequest().getSession(false);
        String token=(String)response.getModelAndView().getModel().get("planToken");
        mvc.perform(get("/maps/live").session(session).param("token",token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.countdowns.length()").value(4)).andExpect(jsonPath("$.active").value(false))
            .andExpect(jsonPath("$.stops.length()").value(4)).andExpect(header().string("Cache-Control",containsString("no-store")));
        mvc.perform(get("/maps/live").param("token",token)).andExpect(status().isOk()).andExpect(jsonPath("$.countdowns.length()").value(0));
        assertEquals(0,saved.count());
    }
    @Test void livePreviewRemainsAvailableAfterTwentyMinutesAndExplainsReplacedPlans() throws Exception {
        var response=mvc.perform(plan()).andReturn();var session=(MockHttpSession)response.getRequest().getSession(false);
        String token=(String)response.getModelAndView().getModel().get("planToken");
        when(clock.instant()).thenReturn(at("08:00"));
        mvc.perform(get("/maps/live").session(session).param("token",token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.planExpired").value(false))
            .andExpect(jsonPath("$.decision.secondBoard").value("7:55 AM"));
        mvc.perform(get("/maps/live").session(session).param("token","replaced"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.planExpired").value(true));
    }
    @Test void activeMapUsesPinnedTripAfterItsLeaveTimeAndRejectsMalformedParameters() throws Exception {
        var session=start();when(clock.instant()).thenReturn(at("07:05"));
        mvc.perform(get("/maps/live").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.phase").value("WALKING_TO_24")).andExpect(jsonPath("$.countdowns[0].id").value("bus24"));
        mvc.perform(get("/maps/live").param("originStop","<script>")).andExpect(status().isBadRequest());
        mvc.perform(get("/maps/live").param("option","-1")).andExpect(status().isBadRequest());
    }
    @Test void rendersLocalMapAssetsAndOptionalPinControls() throws Exception {
        String preview=System.getProperty("jit.map.preview.dir");
        if(preview!=null) {
            var dir=java.nio.file.Path.of(preview);java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("empty.html"),mvc.perform(get("/")).andReturn().getResponse().getContentAsString());
            java.nio.file.Files.writeString(dir.resolve("plan.html"),mvc.perform(plan()).andReturn().getResponse().getContentAsString());
            java.nio.file.Files.writeString(dir.resolve("active.html"),mvc.perform(get("/").session(start())).andReturn().getResponse().getContentAsString());
        }
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(containsString("/vendor/leaflet/leaflet.js")))
            .andExpect(content().string(containsString("Pin starting point"))).andExpect(content().string(containsString("map-auto-refresh")));
        mvc.perform(get("/").session(start())).andExpect(content().string(not(containsString("id=\"map-use-location\""))));
    }

    @Test void savedQuickPlanKeepsMapPointsStopsAndWalksWithoutChangingUsualDeadline() throws Exception {
        var f=new TransitModels.Stop("81799","Houston & Mittman",29.425,-98.454);
        var savedForm=new CommuteForm("Pinned home","Work",DAY,LocalTime.of(9,0),6,3,10,3,5,5,"83463","81799","origin",f.lat(),f.lon(),null,null,"Pinned home",null);
        saved.save(SavedCommute.from(savedForm));
        var result=mvc.perform(post("/daily").with(csrf()).param("date","2026-10-12").param("arriveBy","08:35"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("5 min late"))).andReturn();
        var actual=(CommuteForm)result.getModelAndView().getModel().get("commute");
        assertEquals(6,actual.walkToStop());assertEquals("81799",actual.originStop());assertEquals(f.lat(),actual.originLat());
        assertTrue(actual.isLocationResolved());assertEquals(LocalTime.of(9,0),saved.findById(1L).orElseThrow().arriveBy);
        mvc.perform(post("/daily").param("date","2026-10-12").param("arriveBy","08:35")).andExpect(status().isForbidden());
    }
    @Test void savedTripResumesInNewSessionAndIsDeletedWhenEnded() throws Exception {
        var plan=mvc.perform(plan()).andReturn();var session=(MockHttpSession)plan.getRequest().getSession(false);
        String token=(String)plan.getModelAndView().getModel().get("planToken");
        when(clock.instant()).thenReturn(at("07:04"));
        var started=mvc.perform(post("/journey/start").session(session).with(csrf()).param("token",token).param("option","0"))
            .andExpect(redirectedUrl("/")).andReturn();
        var cookie=started.getResponse().getCookie(JourneyRecovery.COOKIE);assertNotNull(cookie);assertTrue(cookie.isHttpOnly());
        session.invalidate();
        var home=mvc.perform(get("/").cookie(cookie)).andExpect(content().string(containsString("Resume your trip?"))).andReturn();
        var fresh=(MockHttpSession)home.getRequest().getSession(false);
        mvc.perform(post("/journey/resume").session(fresh).cookie(cookie).with(csrf()).param("action","resume")).andExpect(redirectedUrl("/"));
        var view=(ActiveJourney.View)mvc.perform(get("/").session(fresh).cookie(cookie)).andReturn().getModelAndView().getModel().get("journey");
        assertNotNull(view);assertEquals("a",view.option().first().tripId());
        mvc.perform(post("/journey/action").session(fresh).cookie(cookie).with(csrf()).param("revision",view.state().revision()).param("action","end"))
            .andExpect(redirectedUrl("/"));
        mvc.perform(get("/").cookie(cookie)).andExpect(content().string(not(containsString("Resume your trip?"))));
    }
    @Test void resumeDoesNotShareTripWithAnotherBrowserAndExpiresAtEighteenHours() throws Exception {
        var plan=mvc.perform(plan()).andReturn();var session=(MockHttpSession)plan.getRequest().getSession(false);
        when(clock.instant()).thenReturn(at("07:04"));
        var result=mvc.perform(post("/journey/start").session(session).with(csrf()).param("token",(String)plan.getModelAndView().getModel().get("planToken")).param("option","0")).andReturn();
        var cookie=result.getResponse().getCookie(JourneyRecovery.COOKIE);
        mvc.perform(get("/")).andExpect(content().string(not(containsString("Resume your trip?"))));
        when(clock.instant()).thenReturn(at("07:04").plus(Duration.ofHours(18)));
        mvc.perform(get("/").cookie(cookie)).andExpect(content().string(not(containsString("Resume your trip?"))));
        mvc.perform(post("/journey/resume").cookie(cookie).with(csrf()).param("action","resume"))
            .andExpect(flash().attribute("notice",containsString("expired")));
    }

    @Test void dailyPreviewFixtures() throws Exception {
        String preview=System.getProperty("jit.map.preview.dir");if(preview==null)return;
        var dir=java.nio.file.Path.of(preview);java.nio.file.Files.createDirectories(dir);
        var base=schedules.get();var trips=new java.util.ArrayList<>(base.trips());
        trips.add(new TransitModels.Trip("earlier","24","daily","Downtown",java.util.List.of(stop("81799",1,"06:55"),stop("76779",2,"07:25"))));
        when(schedules.get()).thenReturn(new TransitModels.Schedule(base.stops(),trips,base.services(),base.exceptions()));
        when(clock.instant()).thenReturn(at("06:30"));when(clock.withZone(any())).thenReturn(Clock.fixed(at("06:30"),TransitModels.ZONE));
        var pinned=new CommuteForm("Pinned home","Work",DAY,LocalTime.of(9,0),8,3,10,3,5,5,"83463","81799","origin",29.425,-98.454,null,null,"Pinned home",null);
        saved.save(SavedCommute.from(pinned));
        java.nio.file.Files.writeString(dir.resolve("daily.html"),mvc.perform(get("/")).andReturn().getResponse().getContentAsString());
        java.nio.file.Files.writeString(dir.resolve("late.html"),mvc.perform(post("/daily").with(csrf()).param("date","2026-10-12").param("arriveBy","08:35")).andReturn().getResponse().getContentAsString());
    }

    @Test void returnSetupAndSavedSettingsStayIndependent() throws Exception {
        var route=JourneyUpgradeTests.returnSchedule();var base=schedules.get();
        var stops=new java.util.HashMap<>(base.stops());stops.putAll(route.stops());
        var trips=new java.util.ArrayList<>(base.trips());trips.addAll(route.trips());
        when(schedules.get()).thenReturn(new TransitModels.Schedule(stops,trips,base.services(),base.exceptions()));
        saved.save(SavedCommute.from(form()));saved.save(SavedCommute.from(JourneyUpgradeTests.homeForm()));
        mvc.perform(get("/").param("corridor","home")).andExpect(status().isOk()).andExpect(content().string(containsString("Going home")))
            .andExpect(content().string(containsString("Board the 93 at"))).andExpect(content().string(containsString("Get off the 24 at")));
        var result=mvc.perform(post("/daily").with(csrf()).param("corridor","home").param("planMode","depart")
            .param("date","2026-10-12").param("arriveBy","16:50")).andExpect(status().isOk()).andReturn();
        var actual=(CommuteForm)result.getModelAndView().getModel().get("commute");assertEquals("home",actual.corridor());assertEquals("75229",actual.originStop());
        assertEquals(LocalTime.of(17,0),saved.findById(2L).orElseThrow().arriveBy);assertEquals(LocalTime.of(9,0),saved.findById(1L).orElseThrow().arriveBy);
        mvc.perform(post("/forget").with(csrf()).param("corridor","home")).andExpect(redirectedUrl("/?corridor=home"));
        assertTrue(saved.existsById(1L));assertFalse(saved.existsById(2L));
    }
    @Test void returnSetupRequiresUserToEnterBothWalks() throws Exception {
        var response=mvc.perform(get("/").param("corridor","home")).andExpect(status().isOk()).andReturn();
        var f=(CommuteForm)response.getModelAndView().getModel().get("commute");
        assertNull(f.walkToStop());assertNull(f.walkToWork());assertEquals("depart",f.planMode());
        assertEquals("81793",f.destinationStop());assertEquals("75229",f.originStop());assertNull(response.getModelAndView().getModel().get("result"));
    }
    @Test void atStopAndOnboardWebStartsWorkAfterLeaveTime() throws Exception {
        when(clock.instant()).thenReturn(at("07:10"));
        var stopPlan=mvc.perform(plan().param("startMode","stop")).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)stopPlan.getRequest().getSession(false);
        mvc.perform(post("/journey/start").session(session).with(csrf()).param("option","0").param("token",(String)stopPlan.getModelAndView().getModel().get("planToken"))).andExpect(redirectedUrl("/"));
        mvc.perform(get("/").session(session)).andExpect(content().string(containsString("Waiting for the 24")));
        when(clock.instant()).thenReturn(at("07:20"));
        mvc.perform(plan().param("startMode","onboard")).andExpect(model().attributeHasFieldErrors("commute","firstTrip"));
        var onboard=mvc.perform(plan().param("startMode","onboard").param("firstTrip","a@"+DAY)).andExpect(status().isOk()).andReturn();
        session=(MockHttpSession)onboard.getRequest().getSession(false);
        String token=(String)onboard.getModelAndView().getModel().get("planToken");
        mvc.perform(get("/maps/live").session(session).param("token",token)).andExpect(jsonPath("$.countdowns[0].id").value("transfer"));
        mvc.perform(post("/journey/start").session(session).with(csrf()).param("option","0").param("token",token)).andExpect(redirectedUrl("/"));
        mvc.perform(get("/").session(session)).andExpect(content().string(containsString("On the 24")));
    }
    @Test void undoIsRevisionProtectedAndStoredInRecoveryCheckpoint() throws Exception {
        var session=start();var state=((ActiveJourney.View)mvc.perform(get("/").session(session)).andReturn().getModelAndView().getModel().get("journey")).state();
        when(clock.instant()).thenReturn(at("07:15"));
        var progress=mvc.perform(post("/journey/action").session(session).with(csrf()).param("action","advance").param("revision",state.revision())).andReturn();
        var cookie=progress.getResponse().getCookie(JourneyRecovery.COOKIE);session.invalidate();
        var restored=mvc.perform(post("/journey/resume").with(csrf()).cookie(cookie).param("action","resume")).andReturn();
        session=(MockHttpSession)restored.getRequest().getSession(false);
        var view=(ActiveJourney.View)mvc.perform(get("/").session(session)).andReturn().getModelAndView().getModel().get("journey");assertTrue(view.canUndo());
        mvc.perform(post("/journey/action").session(session).with(csrf()).cookie(cookie).param("action","undo").param("revision",view.state().revision())).andExpect(redirectedUrl("/"));
        mvc.perform(post("/journey/action").session(session).with(csrf()).param("action","undo").param("revision",view.state().revision())).andExpect(flash().attribute("notice",containsString("another tab")));
        mvc.perform(get("/").session(session)).andExpect(content().string(containsString("Walking to the 24")));
    }
    @Test void offlineCardContainsOnlyPublicStopsAndInstructionsAndExpires() throws Exception {
        var session=start();
        var card=mvc.perform(get("/journey/offline").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true))
            .andExpect(jsonPath("$.lines.length()").value(4)).andExpect(jsonPath("$.expiresAt").value(at("07:04").plusSeconds(18*3600).toString()))
            .andExpect(header().string("Cache-Control",containsString("no-store"))).andReturn().getResponse().getContentAsString();
        for(String secret:java.util.List.of("Test home","originLat","csrf","revision","JIT_JOURNEY"))assertFalse(card.contains(secret));
        mvc.perform(get("/journey/offline")).andExpect(jsonPath("$.available").value(false));
        when(clock.instant()).thenReturn(at("07:04").plusSeconds(18*3600));
        mvc.perform(get("/journey/offline").session(session)).andExpect(jsonPath("$.available").value(false));
    }
    @Test void historyIsOffByDefaultAndDeletionRequiresCsrf() throws Exception {
        var session=start();assertEquals(0,reports.count());
        mvc.perform(get("/history").session(session)).andExpect(status().isOk()).andExpect(content().string(containsString("No completed trip reports yet")));
        var report=new TripReport();report.id="example";report.owner="test-browser";report.corridor="work";report.travelDate=DAY;report.actualArrival=at("08:40");report.transferOutcome="Made selected transfer";reports.save(report);
        mvc.perform(post("/history/delete").param("id","example")).andExpect(status().isForbidden());assertEquals(1,reports.count());
        mvc.perform(get("/history")).andExpect(status().isOk()).andExpect(content().string(containsString("Not recorded")));
        mvc.perform(post("/history/delete").with(csrf()).param("id","example")).andExpect(redirectedUrl("/history"));assertEquals(0,reports.count());
        mvc.perform(post("/history/clear")).andExpect(status().isForbidden());
        mvc.perform(post("/history/clear").with(csrf())).andExpect(redirectedUrl("/history"));
    }
    @Test void finishingAnOptedInJourneyCreatesOneReport() throws Exception {
        var response=mvc.perform(plan()).andReturn();var session=(MockHttpSession)response.getRequest().getSession(false);
        when(clock.instant()).thenReturn(at("07:04"));
        mvc.perform(post("/journey/start").session(session).with(csrf()).param("token",(String)response.getModelAndView().getModel().get("planToken")).param("option","0").param("keepHistory","true"));
        for(String time:java.util.List.of("07:15","07:45","07:48","07:55","08:30","08:42")) {
            var state=((ActiveJourney.View)mvc.perform(get("/").session(session)).andReturn().getModelAndView().getModel().get("journey")).state();
            when(clock.instant()).thenReturn(at(time));mvc.perform(post("/journey/action").session(session).with(csrf()).param("revision",state.revision()).param("action","advance"));
        }
        assertEquals(0,reports.count());
        var state=((ActiveJourney.View)mvc.perform(get("/").session(session)).andReturn().getModelAndView().getModel().get("journey")).state();
        mvc.perform(post("/journey/action").session(session).with(csrf()).param("revision",state.revision()).param("action","end"));assertEquals(1,reports.count());
        mvc.perform(post("/journey/action").session(session).with(csrf()).param("revision",state.revision()).param("action","end"));assertEquals(1,reports.count());
        assertEquals(at("08:42"),reports.findAll().get(0).actualArrival);
        mvc.perform(get("/journey/offline").session(session)).andExpect(jsonPath("$.available").value(false));
    }
    @Test void upgradePreviewFixtures() throws Exception {
        String preview=System.getProperty("jit.map.preview.dir");if(preview==null)return;
        var dir=java.nio.file.Path.of(preview);java.nio.file.Files.createDirectories(dir);
        when(clock.instant()).thenReturn(at("07:10"));
        java.nio.file.Files.writeString(dir.resolve("stop.html"),mvc.perform(plan().param("startMode","stop")).andReturn().getResponse().getContentAsString());
        when(clock.instant()).thenReturn(at("07:20"));
        java.nio.file.Files.writeString(dir.resolve("onboard.html"),mvc.perform(plan().param("startMode","onboard").param("firstTrip","a@"+DAY)).andReturn().getResponse().getContentAsString());
        when(schedules.get()).thenReturn(JourneyUpgradeTests.returnSchedule());when(clock.instant()).thenReturn(at("16:50"));when(clock.withZone(any())).thenReturn(Clock.fixed(at("16:50"),TransitModels.ZONE));
        java.nio.file.Files.writeString(dir.resolve("home-setup.html"),mvc.perform(get("/").param("corridor","home")).andReturn().getResponse().getContentAsString());
        saved.save(SavedCommute.from(JourneyUpgradeTests.homeForm()));
        java.nio.file.Files.writeString(dir.resolve("home.html"),mvc.perform(get("/").param("corridor","home")).andReturn().getResponse().getContentAsString());
        java.nio.file.Files.writeString(dir.resolve("history.html"),mvc.perform(get("/history")).andReturn().getResponse().getContentAsString());
    }
}
