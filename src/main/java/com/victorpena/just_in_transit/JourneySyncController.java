package com.victorpena.just_in_transit;
import static com.victorpena.just_in_transit.ActiveJourney.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;

@RestController
@RequestMapping("/journey")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="jit.personal.enabled", havingValue="false")
public class JourneySyncController {
    private final JourneySession journeys;private final JourneyActions actions;private final BrowserIdentity browser;private final BrowserCommuteStore saved;private final Clock clock;private final CommuteNotifications notifications;
    public JourneySyncController(JourneySession journeys,JourneyActions actions,BrowserIdentity browser,BrowserCommuteStore saved,Clock clock,CommuteNotifications notifications) {
        this.journeys=journeys;this.actions=actions;this.browser=browser;this.saved=saved;this.clock=clock;this.notifications=notifications;
    }
    public record Status(String scope,Instant now,String journeyId,String revision,String phase,String label,Instant expiresAt,boolean keepHistory,Ride ride,String csrf,String csrfHeader) { }
    @GetMapping("/status") public Status status(HttpServletRequest req) {
        var s=journeys.current(clock.instant());var csrf=(CsrfToken)req.getAttribute(CsrfToken.class.getName());
        return new Status(browser.id(),clock.instant(),s==null?null:s.facts().id(),s==null?null:s.revision(),s==null?null:s.phase().name(),s==null?"No active trip":s.label(),
            s==null?null:s.started().plusSeconds(18*3600),s!=null && s.facts().keepHistory(),s==null?null:s.ride(),csrf.getToken(),csrf.getHeaderName());
    }
    @PostMapping("/sync") public Status sync(@Valid @RequestBody JourneyActions.Update update,HttpServletRequest req,HttpServletResponse res) {
        var form=journeys.lastForm();
        if(form==null || update.corridor()!=null && !form.corridor().equals(update.corridor()))form=saved.findById(Corridor.of(update.corridor()==null?"work":update.corridor()).savedId()).map(s->s.form(LocalDate.now(clock.withZone(TransitModels.ZONE)))).orElse(null);
        var next=actions.apply(req,res,update,form);journeys.restore(next);
        try { notifications.update(browser.id(),next); } catch(RuntimeException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Trip saved; optional reminder update failed",e); }
        return status(req);
    }
    @ExceptionHandler(JourneyConflict.class) public ResponseEntity<Map<String,String>> conflict(JourneyConflict e) { return ResponseEntity.status(409).body(Map.of("message",e.getMessage())); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message",e.getMessage())); }
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class) public ResponseEntity<Map<String,String>> raced(Exception e) { return ResponseEntity.status(409).body(Map.of("message","Another update was saved first. Review the current trip.")); }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) public ResponseEntity<Map<String,String>> unavailable(Exception e) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Trip update could not be committed",e);
        return ResponseEntity.status(503).body(Map.of("message","Save failed. Your update remains on this phone and will retry."));
    }
}
