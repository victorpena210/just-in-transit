package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"jit.personal.enabled=true","via.enabled=false","maps.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:personal-web;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class PersonalCommuteWebTests {
    @Autowired MockMvc mvc;
    private final jakarta.servlet.http.Cookie browserCookie = new jakarta.servlet.http.Cookie(BrowserIdentity.COOKIE,"a".repeat(43));
    @Autowired PersonalCommuteRepository settings;
    @Autowired CommuteRepository oldSettings;
    @MockitoBean Clock clock;
    @MockitoBean ScheduleStore schedules;
    @MockitoBean RealtimeStore realtime;
    @BeforeEach void setup() {
        settings.deleteAll(); oldSettings.deleteAll();
        when(clock.instant()).thenReturn(PersonalPlannerChecks.NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(schedules.get()).thenReturn(PersonalPlannerChecks.connection("07:53:00"));
        when(schedules.message()).thenReturn("Test schedule.");
        when(realtime.get()).thenReturn(RealtimeStore.Snapshot.empty());
        when(realtime.message()).thenReturn("Scheduled times.");
    }
    @Test void homeOnlyAsksForPersonalArrival() throws Exception {
        mvc.perform(get("/").cookie(browserCookie)).andExpect(status().isOk()).andExpect(view().name("commute"))
            .andExpect(content().string(containsString("Make the connection.")))
            .andExpect(content().string(containsString("name=\"_csrf\"")))
            .andExpect(content().string(not(containsString("Trip history"))))
            .andExpect(content().string(not(containsString("leaflet"))));
    }
    @Test void storesSettingsAndRestoresOnFreshVisit() throws Exception {
        mvc.perform(post("/commute").cookie(browserCookie).with(csrf()).param("date","2026-10-12").param("arriveBy","09:00").param("finalWalk","0"))
            .andExpect(redirectedUrl("/"));
        assertEquals(LocalTime.of(9,0),settings.findById(BrowserIdentity.hash(browserCookie.getValue())).orElseThrow().arriveBy);
        mvc.perform(get("/").cookie(browserCookie)).andExpect(status().isOk()).andExpect(content().string(containsString("7:07 AM")))
            .andExpect(content().string(containsString("8 min")));
        mvc.perform(get("/").cookie(new jakarta.servlet.http.Cookie(BrowserIdentity.COOKIE,"b".repeat(43))))
            .andExpect(status().isOk()).andExpect(content().string(not(containsString("7:07 AM"))));
        assertEquals(0,oldSettings.count());
    }
    @Test void invalidFieldsDoNotOverwriteSavedSettings() throws Exception {
        for (String field : new String[]{"date","arriveBy","finalWalk"}) {
            var request=post("/commute").cookie(browserCookie).with(csrf()).param("date","2026-10-12").param("arriveBy","09:00").param("finalWalk","0");
            mvc.perform(request.with(r->{r.setParameter(field,"invalid");return r;}))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("personal",field));
        }
        assertEquals(0,settings.count());
        mvc.perform(post("/commute").cookie(browserCookie).with(csrf()).param("date","2026-10-11").param("arriveBy","09:00").param("finalWalk","0"))
            .andExpect(model().attributeHasFieldErrors("personal","date"));
    }
    @Test void previewRecalculatesWithoutSavingAndNeverRelaxesWaitLimit() throws Exception {
        mvc.perform(get("/commute/preview").param("date","2026-10-12").param("arriveBy","09:00").param("finalWalk","0"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("7:07 AM")));
        when(schedules.get()).thenReturn(PersonalPlannerChecks.connection("08:00:00"));
        mvc.perform(get("/commute/preview").param("date","2026-10-12").param("arriveBy","09:00").param("finalWalk","0"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("No matching connection.")))
            .andExpect(content().string(not(containsString("Leave home at"))));
        assertEquals(0,settings.count());
    }
    @Test void simplifiedModeDisablesOldWorkflowAndRequiresCsrf() throws Exception {
        mvc.perform(get("/history")).andExpect(status().isNotFound());
        mvc.perform(post("/plan").with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/commute").cookie(browserCookie)).andExpect(status().isForbidden());
    }
    @Test void scheduleFailureIsAnHonestEmptyState() throws Exception {
        when(schedules.get()).thenReturn(null);
        mvc.perform(get("/commute/preview").param("date","2026-10-12").param("arriveBy","09:00").param("finalWalk","0"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("schedule is unavailable")));
    }
}
