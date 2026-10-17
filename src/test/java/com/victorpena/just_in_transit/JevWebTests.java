package com.victorpena.just_in_transit;
import static com.victorpena.just_in_transit.JourneyLogicTests.*;
import static com.victorpena.just_in_transit.TransitModels.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.hamcrest.Matchers.containsString;
import java.time.*;import java.util.*;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;import org.springframework.mock.web.MockHttpSession;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.EntitySelector;
import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.TranslatedString;
@SpringBootTest(properties={"jit.personal.enabled=false","via.enabled=false","maps.enabled=false","spring.datasource.url=jdbc:h2:mem:jev-web;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class JevWebTests {
    @Autowired MockMvc mvc;@Autowired JevReviewRepository reviews;
    @MockitoBean ScheduleStore schedules;@MockitoBean RealtimeStore realtime;@MockitoBean AlertStore alerts;@MockitoBean Clock clock;@MockitoBean JevClient jev;
    @BeforeEach void setup()throws Exception{
        reviews.deleteAll();var base=schedule();var stops=new HashMap<String,Stop>();for(String id:List.of("81799","76779","76773","83463"))stops.put(id,new Stop(id,"Public stop "+id,29.43,-98.5));
        when(schedules.get()).thenReturn(new Schedule(stops,base.trips(),base.services(),base.exceptions()));when(realtime.get()).thenReturn(RealtimeStore.Snapshot.empty());when(clock.instant()).thenReturn(at("07:00"));when(clock.withZone(any())).thenReturn(Clock.fixed(at("07:00"),ZONE));when(jev.available()).thenReturn(true);
        var text=TranslatedString.newBuilder().addTranslation(TranslatedString.Translation.newBuilder().setText("Construction near the transfer. Check the stop.")).build();
        var feed=FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(at("07:00").getEpochSecond())).addEntity(FeedEntity.newBuilder().setId("a").setAlert(Alert.newBuilder().addInformedEntity(EntitySelector.newBuilder().setRouteId("93")).setHeaderText(text).setDescriptionText(text))).build();
        when(alerts.get()).thenReturn(AlertStore.Snapshot.parse(feed.toByteArray()));when(alerts.confirmed(any())).thenReturn(true);
    }
    record Plan(MockHttpSession session,String token){}
    Plan plan()throws Exception{return plan("30");}
    Plan plan(String maxEarly)throws Exception{
        var r=mvc.perform(post("/plan").with(csrf()).param("origin","SECRET_HOME_ADDRESS").param("destination","SECRET_WORK_ADDRESS").param("date",DAY.toString()).param("arriveBy","09:00").param("walkToStop","8").param("transferWalk","3").param("walkToWork","10").param("boardingBuffer","3").param("transferBuffer","5").param("arrivalBuffer","5").param("maxEarlyArrival",maxEarly).param("originStop","81799").param("destinationStop","83463")).andExpect(status().isOk()).andReturn();
        return new Plan((MockHttpSession)r.getRequest().getSession(),(String)r.getModelAndView().getModel().get("planToken"));
    }
    @Test void evaluationSendsMinimalContextAndCannotChangePlanOrProgress()throws Exception{
        var p=plan();when(jev.evaluate(anyMap(),anyInt(),anyBoolean())).thenReturn(new JevClient.Evaluation("test-model",List.of(new JevClient.Judgment("Suggested interface action","check_alternatives",0.9,Map.of("check_alternatives",0.9)))));
        mvc.perform(post("/assistant/evaluate").session(p.session()).with(csrf()).param("token",p.token()).param("update","I missed my bus")).andExpect(redirectedUrl("/assistant"));
        var capture=org.mockito.ArgumentCaptor.forClass(Map.class);verify(jev).evaluate(capture.capture(),eq(1),eq(true));String state=capture.getValue().toString();assertFalse(state.contains("SECRET_HOME_ADDRESS"));assertFalse(state.contains("SECRET_WORK_ADDRESS"));assertFalse(state.contains("JIT_BROWSER"));assertTrue(state.contains("I missed my bus"));
        assertEquals(1,reviews.count());assertFalse(reviews.findAll().get(0).judgments.contains("I missed my bus"));
        mvc.perform(get("/assistant").session(p.session())).andExpect(status().isOk()).andExpect(content().string(containsString("Construction near the transfer")));
        mvc.perform(get("/maps/live").session(p.session()).param("token",p.token())).andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.planExpired").value(false));
        mvc.perform(get("/assistant")).andExpect(content().string(org.hamcrest.Matchers.not(containsString("test-model"))));
    }
    @Test void failedJevCallLeavesTheOriginalPlanUsable()throws Exception{
        var p=plan();when(jev.evaluate(anyMap(),anyInt(),anyBoolean())).thenThrow(new java.io.IOException("unavailable"));
        mvc.perform(post("/assistant/evaluate").session(p.session()).with(csrf()).param("token",p.token())).andExpect(flash().attribute("notice",containsString("continues to work")));
        mvc.perform(get("/maps/live").session(p.session()).param("token",p.token())).andExpect(status().isOk()).andExpect(jsonPath("$.planExpired").value(false));assertEquals(0,reviews.count());
    }
    @Test void comparisonIncludesOnlyValidatedChoicesAndNoPrivateAddresses()throws Exception {
        var p=plan();when(jev.compare(anyMap(),anySet())).thenReturn(new JevClient.Evaluation("comparison-model",List.of(new JevClient.Judgment("Suggested departure","option_1",0.9,Map.of("option_1",0.9,"uncertain",0.1)))));
        mvc.perform(post("/assistant/compare").session(p.session()).with(csrf()).param("token",p.token())).andExpect(redirectedUrl("/assistant"));
        var capture=org.mockito.ArgumentCaptor.forClass(Map.class);verify(jev).compare(capture.capture(),eq(Set.of("option_1")));
        String state=capture.getValue().toString();assertFalse(state.contains("SECRET_HOME"));assertFalse(state.contains("SECRET_WORK"));assertTrue(state.contains("max_minutes_early=30"));
        assertEquals(1,reviews.count());assertTrue(reviews.findAll().get(0).evidence.contains("8:40 AM"));
        mvc.perform(get("/maps/live").session(p.session()).param("token",p.token())).andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.planExpired").value(false));
        mvc.perform(post("/assistant/compare").session(p.session()).with(csrf()).param("token",p.token())).andExpect(flash().attribute("notice",containsString("Wait a minute")));
    }
    @Test void outsideArrivalWindowAndUnavailableChoicesNeverReachJev()throws Exception {
        var p=plan("15");mvc.perform(post("/assistant/compare").session(p.session()).with(csrf()).param("token",p.token()))
            .andExpect(flash().attribute("notice",containsString("No current option meets")));
        verify(jev,never()).compare(anyMap(),anySet());
        var valid=plan();var base=schedules.get();when(schedules.get()).thenReturn(new Schedule(base.stops(),List.of(),base.services(),base.exceptions()));
        mvc.perform(post("/assistant/compare").session(valid.session()).with(csrf()).param("token",valid.token()))
            .andExpect(flash().attribute("notice",containsString("No current option meets")));
        verify(jev,never()).compare(anyMap(),anySet());
    }
    @Test void matchingOtherRouteIsHighlightedAndIncludedEvenWhenCorridorDoesNotFit()throws Exception {
        var base=schedules.get();var trips=new ArrayList<>(base.trips());trips.add(new Trip("direct94","94","daily","Direct",List.of(stop("81799",1,"08:00"),stop("83463",2,"08:45"))));
        when(schedules.get()).thenReturn(new Schedule(base.stops(),trips,base.services(),base.exceptions()));var p=plan("15");
        mvc.perform(get("/").session(p.session())).andExpect(content().string(containsString("A matching option on another route")));
        // Rendering makes a fresh token; use the session's current plan, not the replaced token.
        var fresh=mvc.perform(get("/").session(p.session())).andReturn();String token=(String)fresh.getModelAndView().getModel().get("planToken");
        when(jev.compare(anyMap(),anySet())).thenReturn(new JevClient.Evaluation("other-route-model",List.of(new JevClient.Judgment("Suggested departure","option_1",0.9,Map.of("option_1",0.9,"uncertain",0.1)))));
        mvc.perform(post("/assistant/compare").session(p.session()).with(csrf()).param("token",token)).andExpect(redirectedUrl("/assistant"));
        var capture=org.mockito.ArgumentCaptor.forClass(Map.class);verify(jev).compare(capture.capture(),eq(Set.of("option_1")));
        String state=capture.getValue().toString();assertTrue(state.contains("routes=94"));assertFalse(state.contains("routes=24 → 93"));
    }
    @Test void comparisonFailureOrUnknownSelectionKeepsStandardPlanUsable()throws Exception {
        var p=plan();when(jev.compare(anyMap(),anySet())).thenReturn(new JevClient.Evaluation("model",List.of(new JevClient.Judgment("Suggested departure","invented_bus",1,Map.of()))));
        mvc.perform(post("/assistant/compare").session(p.session()).with(csrf()).param("token",p.token())).andExpect(flash().attribute("notice",containsString("continues to work")));
        assertEquals(0,reviews.count());mvc.perform(get("/maps/live").session(p.session()).param("token",p.token())).andExpect(jsonPath("$.planExpired").value(false));
    }
    @Test void disabledJevShowsStatusAndDoesNotOfferAnEvaluationButton()throws Exception {
        when(jev.available()).thenReturn(false);var p=plan();
        mvc.perform(get("/").session(p.session())).andExpect(content().string(containsString("Jev is not enabled")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Ask Jev to compare these departures"))));
        mvc.perform(post("/assistant/compare").session(p.session()).with(csrf()).param("token",p.token())).andExpect(flash().attribute("notice",containsString("not enabled")));
        verify(jev,never()).compare(anyMap(),anySet());
    }
}
