package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import static com.victorpena.just_in_transit.PlannerTests.stop;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import java.time.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import com.google.transit.realtime.GtfsRealtime;

@SpringBootTest(properties={"via.enabled=false","maps.enabled=false","spring.datasource.url=jdbc:h2:mem:boundary-web;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class BoundaryWebTests {
    @Autowired MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean BrowserIdentity browser;
    @Autowired BrowserCommuteStore repository;
    @MockitoBean ScheduleStore schedules;
    @MockitoBean AlertStore alerts;
    static final LocalDate DAY=LocalDate.of(2030,1,2);
    @BeforeEach void fixture() throws Exception { org.mockito.Mockito.when(browser.id()).thenReturn("test-browser");
        repository.deleteAll();
        var s=new Schedule(Map.of("81799",new Stop("81799","E. HOUSTON & MITTMAN",29.425194,-98.454422),
            "76779",new Stop("76779","MARTIN & LEONA",29.430046,-98.501998),
            "76773",new Stop("76773","MARTIN OPP LEONA",29.42979,-98.502302),
            "83463",new Stop("83463","FIESTA TEXAS EMPLOYEE ENTRANCE",29.593,-98.608)),
            List.of(new Trip("a","24","daily","Downtown",List.of(stop("81799",1,"07:15:00"),stop("76779",2,"07:45:00")),"0"),
                new Trip("b","93","daily","Six Flags",List.of(stop("76773",1,"07:55:00"),stop("83463",2,"08:30:00")),"0"),
                new Trip("later","93","daily","Six Flags",List.of(stop("76773",1,"08:55:00"),stop("83463",2,"09:30:00")),"0")),
            Map.of("daily",new Service(DAY,DAY,EnumSet.allOf(DayOfWeek.class))),Map.of(),Map.of("24",new RouteInfo("VIA",3),"93",new RouteInfo("VIA",3)));
        when(schedules.get()).thenReturn(s);when(schedules.message()).thenReturn("Synthetic test schedule");
        var feed=GtfsRealtime.FeedMessage.newBuilder().setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(Instant.now().getEpochSecond()))
            .addEntity(GtfsRealtime.FeedEntity.newBuilder().setId("advisory").setAlert(GtfsRealtime.Alert.newBuilder()
                .addInformedEntity(GtfsRealtime.EntitySelector.newBuilder().setRouteId("93"))
                .setHeaderText(text("Allow extra time at the transfer"))
                .setDescriptionText(text("Synthetic notice for interface testing. <script>alert('unsafe')</script>"))));
        when(alerts.get()).thenReturn(AlertStore.Snapshot.parse(feed.build().toByteArray()));
        when(alerts.status(org.mockito.ArgumentMatchers.any())).thenReturn("Synthetic service-alert feed checked.");
        when(alerts.confirmed(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    }
    private GtfsRealtime.TranslatedString text(String value) {
        return GtfsRealtime.TranslatedString.newBuilder().addTranslation(GtfsRealtime.TranslatedString.Translation.newBuilder().setText(value)).build();
    }
    private MockHttpServletRequestBuilder form(String endpoint) {
        return post(endpoint).with(csrf()).param("origin","Example starting point").param("destination","Example clock-in location")
            .param("date","2030-01-02").param("arriveBy","09:00").param("walkToStop","8").param("transferWalk","3")
            .param("walkToWork","10").param("boardingBuffer","3").param("transferBuffer","5").param("arrivalBuffer","5")
            .param("originStop","81799").param("destinationStop","83463");
    }
    @Test void rendersBackupArrivalAdvisoriesEscapedAndAttribution() throws Exception {
        var response=mvc.perform(form("/plan")).andExpect(status().isOk())
            .andExpect(content().string(containsString("9:40 AM"))).andExpect(content().string(containsString("40 min late")))
            .andExpect(content().string(containsString("Data provided by VIA Metropolitan Transit")))
            .andExpect(content().string(containsString("&lt;script&gt;"))).andReturn();
        String output=System.getProperty("jit.preview.html");
        if(output!=null)Files.writeString(Path.of(output),response.getResponse().getContentAsString());
    }
    @Test void unknownStopIsRejectedBeforeSaving() throws Exception {
        mvc.perform(form("/save").param("originStop","unknown")).andExpect(status().isOk()).andExpect(model().attributeHasErrors("commute"));
        Assertions.assertEquals(0,repository.count());
    }
    @Test void addressModeRequiresResolvedMatchingAddresses() throws Exception {
        mvc.perform(form("/save").param("locationMode","address")).andExpect(status().isOk()).andExpect(model().attributeHasErrors("commute"));
        Assertions.assertEquals(0,repository.count());
    }
    @Test void confirmedStartCanBeSavedWithManualDestinationAndUsesCoordinatesForWalkingLink() throws Exception {
        var response=mvc.perform(form("/save").param("locationMode","origin")
            .param("originLat","29.425194").param("originLon","-98.454422").param("resolvedOrigin","Example starting point"))
            .andExpect(redirectedUrl("/")).andReturn();
        mvc.perform(get("/").flashAttrs(response.getFlashMap())).andExpect(status().isOk())
            .andExpect(content().string(containsString("origin=29.425194%2C-98.454422")));
        var restored=repository.findById(1L).orElseThrow().form(DAY);
        Assertions.assertTrue(restored.usesAddress("origin"));Assertions.assertFalse(restored.usesAddress("destination"));
        Assertions.assertTrue(restored.isLocationResolved());Assertions.assertNull(restored.destinationLat());
    }
    @Test void confirmedDestinationCanBeSavedWithManualStart() throws Exception {
        var response=mvc.perform(form("/save").param("locationMode","destination")
            .param("destinationLat","29.593").param("destinationLon","-98.608").param("resolvedDestination","Example clock-in location"))
            .andExpect(redirectedUrl("/")).andReturn();
        mvc.perform(get("/").flashAttrs(response.getFlashMap())).andExpect(status().isOk())
            .andExpect(content().string(containsString("destination=29.593%2C-98.608")));
        var restored=repository.findById(1L).orElseThrow().form(DAY);
        Assertions.assertFalse(restored.usesAddress("origin"));Assertions.assertTrue(restored.usesAddress("destination"));
        Assertions.assertTrue(restored.isLocationResolved());
    }
    @Test void incompleteOrChangedSingleEndPointCannotBeSaved() throws Exception {
        for(String mode:List.of("origin","destination")) {
            mvc.perform(form("/save").param("locationMode",mode))
                .andExpect(status().isOk()).andExpect(model().attributeHasErrors("commute"));
        }
        mvc.perform(form("/save").param("locationMode","origin").param("originLat","29.425194")
            .param("originLon","-98.454422").param("resolvedOrigin","Old starting point"))
            .andExpect(status().isOk()).andExpect(model().attributeHasErrors("commute"));
        Assertions.assertEquals(0,repository.count());
    }
    @Test void singleEndPointStillRequiresNearbyEligibleStop() throws Exception {
        mvc.perform(form("/save").param("locationMode","origin").param("originLat","29.8")
            .param("originLon","-98.8").param("resolvedOrigin","Example starting point"))
            .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("commute","originStop"));
        Assertions.assertEquals(0,repository.count());
    }
    @Test void mapEndpointsRequireCsrfAndDoNotExposeRawProviderErrors() throws Exception {
        mvc.perform(post("/maps/search").contentType("application/json").content("{\"query\":\"San Antonio\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/maps/search").with(csrf()).contentType("application/json").content("{\"query\":\"San Antonio\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value(containsString("Map lookup unavailable")));
    }
    @Test void nearbyEndpointUsesCoordinatesAndRejectsNonLocalInput() throws Exception {
        mvc.perform(post("/maps/stops").with(csrf()).contentType("application/json")
            .content("{\"kind\":\"origin\",\"lat\":29.425,\"lon\":-98.454,\"date\":\"2030-01-02\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("81799"));
        mvc.perform(post("/maps/stops").with(csrf()).contentType("application/json")
            .content("{\"kind\":\"origin\",\"lat\":52,\"lon\":13,\"date\":\"2030-01-02\"}"))
            .andExpect(status().isBadRequest());
    }
    @Test void manualSettingsCanBeRestoredWithIncompleteForm() throws Exception {
        mvc.perform(post("/settings").with(csrf()).param("locationMode","manual")).andExpect(status().isOk());
    }
    @Test void stopSearchWorksWithExternalMapsDisabledAndChecksTheDate() throws Exception {
        mvc.perform(post("/maps/stop-search").with(csrf()).contentType("application/json")
            .content("{\"kind\":\"origin\",\"query\":\"Houston & Mittman\",\"date\":\"2030-01-02\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("81799"));
        mvc.perform(post("/maps/stop-search").with(csrf()).contentType("application/json")
            .content("{\"kind\":\"origin\",\"query\":\"Mittman\",\"date\":\"2030-01-03\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
    @Test void stopSearchRequiresCsrfAndValidInputs() throws Exception {
        mvc.perform(post("/maps/stop-search").contentType("application/json")
            .content("{\"kind\":\"origin\",\"query\":\"Mittman\",\"date\":\"2030-01-02\"}"))
            .andExpect(status().isForbidden());
        for(String body:List.of("{\"kind\":\"transfer\",\"query\":\"Mittman\",\"date\":\"2030-01-02\"}",
                "{\"kind\":\"origin\",\"query\":\" \u0020\",\"date\":\"2030-01-02\"}","{\"kind\":\"origin\",\"query\":\"Mittman\"}"))
            mvc.perform(post("/maps/stop-search").with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }
}
