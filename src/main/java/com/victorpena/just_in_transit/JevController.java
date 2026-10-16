package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;import java.util.*;
import org.springframework.stereotype.Controller;import org.springframework.ui.Model;import org.springframework.web.bind.annotation.*;import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpSession;

@Controller public class JevController {
    private final JevClient jev;private final JevReviewRepository reviews;private final BrowserIdentity browser;private final JourneySession journeys;private final ScheduleStore schedules;private final RealtimeStore realtime;private final AlertStore alerts;private final Clock clock;
    private final JsonMapper json=JsonMapper.builder().build();
    public JevController(JevClient jev,JevReviewRepository reviews,BrowserIdentity browser,JourneySession journeys,ScheduleStore schedules,RealtimeStore realtime,AlertStore alerts,Clock clock) { this.jev=jev;this.reviews=reviews;this.browser=browser;this.journeys=journeys;this.schedules=schedules;this.realtime=realtime;this.alerts=alerts;this.clock=clock; }
    public record Evidence(String title,String description,String effect) { }
    public record Row(JevReview record,List<JevClient.Judgment> judgments,List<Evidence> evidence) { }
    @GetMapping("/assistant") public String page(Model model,@RequestParam(defaultValue="") String token,@RequestParam(defaultValue="0") int option) {
        model.addAttribute("available",jev.available());model.addAttribute("token",token.length()>100?"":token);model.addAttribute("option",Math.max(0,Math.min(2,option)));
        reviews.deleteByExpiresAtBefore(clock.instant());
        model.addAttribute("reviews",reviews.findTop20ByOwnerOrderByCreatedDesc(browser.id()).stream().map(r->new Row(r,Arrays.asList(json.readValue(r.judgments,JevClient.Judgment[].class)),r.evidence==null?List.of():Arrays.asList(json.readValue(r.evidence,Evidence[].class)))).toList());return "assistant";
    }
    @PostMapping("/assistant/evaluate") public String evaluate(@RequestParam(defaultValue="") String token,@RequestParam(defaultValue="0") int option,
        @RequestParam(defaultValue="") String update,HttpSession session,RedirectAttributes flash) {
        Instant now=clock.instant();
        if(update.length()>500) {flash.addFlashAttribute("notice","Keep your update under 500 characters.");return "redirect:/assistant";}
        synchronized(session) {var last=(Instant)session.getAttribute("jev.lastAttempt");if(last!=null && now.isBefore(last.plusSeconds(60))) {flash.addFlashAttribute("notice","Wait a minute before another evaluation.");return "redirect:/assistant";}session.setAttribute("jev.lastAttempt",now);}
        var active=journeys.current(now);var choice=active==null?journeys.mapChoice(token,option,now):null;
        if(active==null && choice==null) {flash.addFlashAttribute("notice","Open a current departure plan or active trip first.");return "redirect:/assistant";}
        var schedule=schedules.get();var live=realtime.get();CommuteForm form=active!=null?active.form():choice.form();
        Option selected=active!=null?ActiveJourney.view(active,schedule,live,now).option():ActiveJourney.preview(form,choice.option(),schedule,live,now);
        var contexts=AlertStore.contexts(form,schedule,new Result(List.of(selected),""),now);var snapshot=alerts.get();
        var relevant=alerts.confirmed(now)?snapshot.relevant(contexts).stream().limit(3).toList():List.<AlertStore.Advisory>of();
        if(relevant.isEmpty() && update.isBlank()) {flash.addFlashAttribute("notice","No current matching service advisory is available to evaluate. Your regular plan remains available.");return "redirect:/assistant";}
        var trip=new LinkedHashMap<String,Object>();trip.put("routes",form.route().routes());trip.put("date",form.date().toString());trip.put("phase",active==null?form.startMode():active.label());
        trip.put("stops",contexts.stream().flatMap(c->c.stops().stream()).distinct().map(id->schedule!=null && schedule.stops().containsKey(id)?schedule.stops().get(id).name():id).toList());
        trip.put("directions",contexts.stream().map(AlertStore.Context::direction).toList());trip.put("transfer_minutes_after_walk",selected.transferMargin());trip.put("required_transfer_minutes",form.transferBuffer());
        trip.put("has_arrival_deadline",!form.departing());if(!form.departing())trip.put("minutes_before_deadline",selected.arrivalMargin());trip.put("prediction_source",selected.dataLabel());trip.put("live_feed_fresh",live.fresh(now));
        var state=new LinkedHashMap<String,Object>();state.put("trip",trip);state.put("advisories",relevant.stream().map(a->Map.of("title",clip(a.title(),300),"description",clip(a.description(),3000),"effect",a.effect())).toList());
        if(!update.isBlank())state.put("rider_update",update);
        try {
            var result=jev.evaluate(state,relevant.size(),!update.isBlank());var row=new JevReview();row.id=UUID.randomUUID().toString();row.owner=browser.id();row.created=now;row.expiresAt=now.plus(Duration.ofDays(30));row.model=result.model();row.judgments=json.writeValueAsString(result.judgments());row.evidence=json.writeValueAsString(relevant.stream().map(a->new Evidence(clip(a.title(),300),clip(a.description(),3000),a.effect())).toList());reviews.save(row);
            flash.addFlashAttribute("notice","Evaluation recorded. Your route, warnings and trip progress were not changed.");
        } catch(Exception e) {flash.addFlashAttribute("notice","Jev could not evaluate this request. Your regular commute planner continues to work.");org.slf4j.LoggerFactory.getLogger(getClass()).warn("Jev evaluation unavailable ({})",e.getClass().getSimpleName());}
        return "redirect:/assistant";
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000,initialDelay=3600000) public void expireReviews() { reviews.deleteByExpiresAtBefore(clock.instant()); }
    static String clip(String text,int limit) {return text==null?"":text.substring(0,Math.min(limit,text.length()));}
    @PostMapping("/assistant/feedback") public String feedback(@RequestParam String id,@RequestParam String feedback) {
        if(!Set.of("helpful","not_helpful","incorrect","uncertain").contains(feedback))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        reviews.findByIdAndOwner(id,browser.id()).ifPresent(r->{r.feedback=feedback;reviews.save(r);});return "redirect:/assistant";
    }
    @PostMapping("/assistant/clear") public String clear() {reviews.deleteByOwner(browser.id());return "redirect:/assistant";}
}
