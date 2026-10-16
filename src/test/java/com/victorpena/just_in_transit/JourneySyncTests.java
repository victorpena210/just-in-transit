package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.JourneyLogicTests.*;
import static com.victorpena.just_in_transit.TransitModels.*;
import static com.victorpena.just_in_transit.BrowserIsolationTests.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties={"via.enabled=false","maps.enabled=false","spring.datasource.url=jdbc:h2:mem:sync;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class JourneySyncTests {
    @Autowired MockMvc mvc;
    @Autowired JourneyCheckpointRepository checkpoints;
    @MockitoSpyBean JourneyReceiptRepository receipts;
    @Autowired TripReportRepository reports;
    @Autowired BrowserCommuteRepository settings;
    @MockitoBean ScheduleStore schedules;
    @MockitoBean VehicleStore vehicles;
    @MockitoBean Clock clock;
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void setup() {
        checkpoints.deleteAll();receipts.deleteAll();reports.deleteAll();settings.deleteAll();
        when(clock.instant()).thenReturn(at("08:00"));when(clock.withZone(any())).thenReturn(Clock.fixed(at("08:00"),ZONE));
        var base=schedule();var trips=new ArrayList<>(base.trips());trips.add(new Trip("loop","93","daily","Downtown loop",List.of(stop("76773",1,"08:01"))));
        var stops=Map.of("81799",new Stop("81799","First stop",29.43,-98.45),"76779",new Stop("76779","Transfer off",29.43,-98.50),"76773",new Stop("76773","Transfer on",29.43,-98.50),"83463",new Stop("83463","Fiesta Texas",29.59,-98.60));
        when(schedules.get()).thenReturn(new Schedule(stops,trips,base.services(),base.exceptions()));
        feed("loop",at("08:00"),"20261012");
        var saved=new BrowserCommute();saved.id=owner(A)+":1";saved.owner=owner(A);saved.payload=json.writeValueAsString(SavedCommute.from(form()));settings.save(saved);
    }
    void feed(String trip,Instant stamp,String date) { when(vehicles.get()).thenReturn(new VehicleStore.Snapshot(stamp,List.of(new VehicleStore.Position("9301",trip,"93",date,29.43,-98.49,stamp)))); }
    Map<String,Object> update(JsonNode status,String action,String time) {
        var map=new LinkedHashMap<String,Object>();map.put("eventId",UUID.randomUUID().toString());map.put("action",action);map.put("recordedAt",at(time).toString());map.put("corridor","work");
        if(status!=null)for(String key:List.of("journeyId","revision","phase"))if(!status.path(key).isNull())map.put(key.equals("phase")?"expectedPhase":key,status.path(key).asText());
        return map;
    }
    Map<String,Object> select() { var u=update(null,"selectBus","08:00");u.put("route","93");u.put("vehicleId","9301");u.put("driverConfirmed",true);u.put("keepHistory",true);return u; }
    ResultActions send(Map<String,Object> u) throws Exception { return mvc.perform(post("/journey/sync").cookie(A).with(csrf()).contentType("application/json").content(json.writeValueAsString(u))); }
    JsonNode result(ResultActions action) throws Exception { return json.readTree(action.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    JsonNode current() throws Exception { return result(mvc.perform(get("/journey/status").cookie(A))); }
    ActiveJourney.State stored() { return json.readValue(checkpoints.findAll().get(0).payload,ActiveJourney.State.class); }

    @Test void manualSecondBusRestoresWithoutSessionOrJourneyCookieAndDoesNotInventBoardingTimes() throws Exception {
        var selected=result(send(select()));assertEquals("ON_93",selected.path("phase").asText());assertEquals("9301",current().path("ride").path("vehicleId").asText());
        var state=stored();assertEquals(Set.of("onSecondConfirmed"),state.facts().confirmations().keySet());assertNull(state.facts().plannedDeparture());assertNull(state.facts().plannedArrival());assertFalse(state.option().second().alight().usable());
        assertEquals("In progress",reports.findAll().get(0).status);assertNull(reports.findAll().get(0).actualArrival);
        mvc.perform(get("/").cookie(A)).andExpect(status().isOk()).andExpect(model().attributeExists("journey"));
        mvc.perform(get("/journey/status").cookie(B)).andExpect(jsonPath("$.phase").doesNotExist());
        mvc.perform(get("/journey/status").cookie(B,new jakarta.servlet.http.Cookie(JourneyRecovery.COOKIE,checkpoints.findAll().get(0).id))).andExpect(jsonPath("$.phase").doesNotExist());
    }
    @Test void legacyCheckpointWithoutRideFieldRecoversByOwnerAfterSessionAndCookieLoss() throws Exception {
        result(send(select()));var original=checkpoints.findAll().get(0);var payload=(tools.jackson.databind.node.ObjectNode)json.readTree(original.payload);payload.remove("ride");
        checkpoints.deleteAll();var legacy=new JourneyCheckpoint();legacy.id=UUID.randomUUID().toString();legacy.owner=owner(A);legacy.payload=json.writeValueAsString(payload);legacy.expiresAt=original.expiresAt;checkpoints.saveAndFlush(legacy);
        assertEquals("ON_93",current().path("phase").asText());mvc.perform(get("/journey/status").cookie(B)).andExpect(jsonPath("$.phase").doesNotExist());
    }
    @Test void sameVehicleSurvivesLoopAndTripChangeButStaleOrAmbiguousFeedCannotPromiseArrival() throws Exception {
        var selected=result(send(select()));var revision=selected.path("revision").asText();
        mvc.perform(get("/maps/live").cookie(A)).andExpect(jsonPath("$.candidates[0].headsign").value("Downtown loop")).andExpect(jsonPath("$.buses[0].selected").value(true));
        feed("b",at("08:00"),"20261012");
        var home=mvc.perform(get("/").cookie(A)).andReturn();var view=(ActiveJourney.View)home.getModelAndView().getModel().get("journey");
        assertTrue(view.arrivalAvailable());assertEquals(at("08:30"),view.option().second().alight().at());assertEquals(revision,view.state().revision());
        for(String date:List.of("","20261011")) { feed("b",at("08:00"),date);home=mvc.perform(get("/").cookie(A)).andReturn();view=(ActiveJourney.View)home.getModelAndView().getModel().get("journey");assertFalse(view.arrivalAvailable()); }
        feed("b",at("07:57"),"20261012");home=mvc.perform(get("/").cookie(A)).andReturn();view=(ActiveJourney.View)home.getModelAndView().getModel().get("journey");assertFalse(view.arrivalAvailable());assertEquals(ActiveJourney.Phase.ON_93,view.state().phase());
    }
    @Test void retryIsIdempotentAndQueuedActionsRetainTheirRecordedTimes() throws Exception {
        var selected=result(send(select()));when(clock.instant()).thenReturn(at("08:20"));
        var off=update(selected,"advance","08:10");var walking=result(send(off));
        assertEquals("WALKING_TO_DESTINATION",walking.path("phase").asText());assertNull(stored().ride());assertEquals(at("08:10"),stored().facts().confirmations().get("WALKING_TO_DESTINATION"));
        assertEquals(walking.path("revision"),result(send(off)).path("revision"));assertEquals(2,receipts.count());
        var altered=new LinkedHashMap<>(off);altered.put("action","end");send(altered).andExpect(status().isConflict());
        send(update(selected,"advance","08:15")).andExpect(status().isConflict());
        send(update(walking,"advance","08:09")).andExpect(status().isConflict());
        var arrived=result(send(update(walking,"advance","08:18")));assertEquals("ARRIVED",arrived.path("phase").asText());assertEquals(at("08:18"),reports.findAll().get(0).actualArrival);
        result(send(update(arrived,"end","08:20")));assertEquals(0,checkpoints.count());assertTrue(current().path("phase").isNull());
        result(send(off));assertEquals(0,checkpoints.count()); // A late duplicate cannot resurrect the old trip.
    }
    @Test void databaseFailureRollsBackCheckpointHistoryAndReceiptAndCanRetry() throws Exception {
        var selected=result(send(select()));String payload=checkpoints.findAll().get(0).payload;
        when(clock.instant()).thenReturn(at("08:10"));var off=update(selected,"advance","08:10");
        doThrow(new org.springframework.dao.DataIntegrityViolationException("Synthetic commit failure")).when(receipts).saveAndFlush(any());
        send(off).andExpect(status().isServiceUnavailable());assertEquals(payload,checkpoints.findAll().get(0).payload);assertEquals(1,receipts.count());assertEquals("ON_93",current().path("phase").asText());
        reset(receipts);result(send(off));assertEquals("WALKING_TO_DESTINATION",current().path("phase").asText());
    }
    @Test void invalidBusInputAndUnprotectedWritesCannotChangeStatus() throws Exception {
        var u=select();u.remove("route");send(u).andExpect(status().isBadRequest());assertEquals(0,checkpoints.count());
        u=select();u.put("recordedAt",at("08:02").toString());send(u).andExpect(status().isConflict());
        mvc.perform(post("/journey/sync").cookie(A).contentType("application/json").content(json.writeValueAsString(select()))).andExpect(status().isForbidden());
    }
    @Test void endingEarlyKeepsOptedInReportAndHistoryCanBeTurnedOff() throws Exception {
        var state=result(send(select()));result(send(update(state,"end","08:00")));assertEquals("Ended before arrival",reports.findAll().get(0).status);
        state=result(send(select()));var off=update(state,"history","08:00");off.put("keepHistory",false);result(send(off));assertEquals(1,reports.count());assertFalse(stored().facts().keepHistory());
    }
}
