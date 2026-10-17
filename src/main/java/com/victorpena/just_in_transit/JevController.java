package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;import java.util.*;
import org.springframework.stereotype.Controller;import org.springframework.ui.Model;import org.springframework.web.bind.annotation.*;import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpSession;

@Controller @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="jit.personal.enabled", havingValue="false")
public class JevController {
    private final JevClient jev;private final JevReviewRepository reviews;private final BrowserIdentity browser;private final JourneySession journeys;private final ScheduleStore schedules;private final RealtimeStore realtime;private final AlertStore alerts;private final Clock clock;
    private final JsonMapper json=JsonMapper.builder().build();
    @org.springframework.beans.factory.annotation.Autowired private AlternativePlanner alternatives;
    public JevController(JevClient jev,JevReviewRepository reviews,BrowserIdentity browser,JourneySession journeys,ScheduleStore schedules,RealtimeStore realtime,AlertStore alerts,Clock clock) { this.jev=jev;this.reviews=reviews;this.browser=browser;this.journeys=journeys;this.schedules=schedules;this.realtime=realtime;this.alerts=alerts;this.clock=clock; }
    public record Evidence(String title,String description,String effect) { }
    public record Row(JevReview record,List<JevClient.Judgment> judgments,List<Evidence> evidence) {
        public String suggestedDeparture() {
            var choice=judgments.stream().filter(j->j.label().equals("Suggested departure")).findFirst().orElse(null);
            if(choice==null)return "";
            if(choice.choice().equals("uncertain"))return "Jev could not choose a departure from the available evidence. Review the planner's options.";
            return evidence.stream().filter(e->e.title().equals(choice.choice())).map(Evidence::description).findFirst().orElse("");
        }
    }
    @GetMapping("/assistant") public String page(Model model,@RequestParam(defaultValue="") String token,@RequestParam(defaultValue="0") int option) {
        model.addAttribute("available",jev.available());model.addAttribute("token",token.length()>100?"":token);model.addAttribute("option",Math.max(0,Math.min(2,option)));
        reviews.deleteByExpiresAtBefore(clock.instant());
        model.addAttribute("reviews",reviews.findTop20ByOwnerOrderByCreatedDesc(browser.id()).stream().map(r->new Row(r,Arrays.asList(json.readValue(r.judgments,JevClient.Judgment[].class)),r.evidence==null?List.of():Arrays.asList(json.readValue(r.evidence,Evidence[].class)))).toList());return "assistant";
    }
    @PostMapping("/assistant/evaluate") public String evaluate(@RequestParam(defaultValue="") String token,@RequestParam(defaultValue="0") int option,
        @RequestParam(defaultValue="") String update,HttpSession session,RedirectAttributes flash) {
        Instant now=clock.instant();
        if(!jev.available()) {flash.addFlashAttribute("notice","Jev is not enabled. Your standard departure planner is available.");return "redirect:/assistant";}
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
    @PostMapping("/assistant/compare") public String compare(@RequestParam String token,HttpSession session,RedirectAttributes flash) {
        Instant now=clock.instant();
        if(!jev.available()) {flash.addFlashAttribute("notice","Jev is not enabled. Your standard departure planner is available.");return "redirect:/assistant";}
        var f=journeys.offeredForm(token,now);
        if(f==null || journeys.current(now)!=null) {flash.addFlashAttribute("notice","Find fresh departures before comparing options with Jev.");return "redirect:/assistant";}
        synchronized(session) {var last=(Instant)session.getAttribute("jev.lastAttempt");if(last!=null && now.isBefore(last.plusSeconds(60))) {flash.addFlashAttribute("notice","Wait a minute before another evaluation.");return "redirect:/assistant";}session.setAttribute("jev.lastAttempt",now);}
        var schedule=schedules.get();var live=realtime.get();
        if(schedule==null) {flash.addFlashAttribute("notice","The schedule is unavailable. Refresh the departure plan before comparing.");return "redirect:/assistant";}
        var options=new ArrayList<Map<String,Object>>();var evidence=new ArrayList<Evidence>();var contexts=new ArrayList<AlertStore.Context>();
        for(int i=0;i<3;i++) {
            var choice=journeys.mapChoice(token,i,now);if(choice==null)continue;
            var o=ActiveJourney.preview(f,choice.option(),schedule,live,now);
            boolean usable=List.of(o.first().board(),o.first().alight(),o.second().board(),o.second().alight()).stream().allMatch(Event::usable);
            boolean joinable=f.onboard()?CommutePlanner.joinable(o.first(),now):!o.leaveHome().isBefore(now) && !o.first().board().at().isBefore(now);
            if(!usable || !joinable || !o.meetsBuffers())continue;
            String id="option_"+(options.size()+1);var timing=o.timing(f);
            options.add(comparisonOption(id,f.route().routes(),o.leaveHome(),o.atWork(),timing.firstWait(),o.transferWait(),f.transferWalk(),o.dataLabel()));
            evidence.add(new Evidence(id,f.route().routes()+" · leave "+o.leaveDisplay()+" · destination "+o.workDisplay()+" · "+timing.firstWait()+" min first-stop wait · "+o.transferWait()+" min transfer wait","Validated departure"));
            contexts.addAll(AlertStore.contexts(f,schedule,new Result(List.of(o),""),now));
        }
        for(var c:alternatives.forCommute(schedule,live,f,now)) {
            if(!c.fits())continue;
            String id="option_"+(options.size()+1);
            options.add(comparisonOption(id,c.routes(),c.leave(),c.arrival(),f.boardingBuffer(),c.waitMinutes(),c.transferMinutes(),"VIA schedule / available live estimates"));
            evidence.add(new Evidence(id,c.routes()+" · leave "+c.leaveDisplay()+" · destination "+c.arrivalDisplay()+" · "+f.boardingBuffer()+" min first-stop wait · "+c.waitMinutes()+" min transfer wait","Validated departure"));
            for(var ride:c.rides())contexts.add(AlertStore.context(schedule,new Leg(ride.trip().route(),ride.trip().id(),ride.board(),ride.alight(),ride.day()),ride.from().id(),ride.to().id()));
        }
        if(options.isEmpty()) {flash.addFlashAttribute("notice","No current option meets all your arrival and wait limits. Review the planner's trade-offs or adjust your preferences before asking Jev.");return "redirect:/assistant";}
        var relevant=alerts.confirmed(now)?alerts.get().relevant(contexts).stream().limit(3).toList():List.<AlertStore.Advisory>of();
        evidence.addAll(relevant.stream().map(a->new Evidence(clip(a.title(),300),clip(a.description(),3000),a.effect())).toList());
        var state=new LinkedHashMap<String,Object>();state.put("options",options);
        state.put("preferences",Map.of("ranking",f.departing()?"earliest_arrival":f.preference(),"max_minutes_early",f.maxEarlyArrival(),"arrival_cushion",f.arrivalBuffer(),"extra_delay_per_bus",f.trafficAllowance(),"first_wait_min",f.boardingBuffer(),"first_wait_max",f.maxBoardingWait(),"transfer_wait_min",f.transferBuffer(),"transfer_wait_max",f.maxTransferWait()));
        state.put("checked_at",now.toString());state.put("live_feed_fresh",live.fresh(now));
        state.put("advisories",relevant.stream().map(a->Map.of("title",clip(a.title(),300),"description",clip(a.description(),3000),"effect",a.effect())).toList());
        Set<String> ids=new HashSet<>();options.forEach(o->ids.add((String)o.get("id")));
        try {
            var result=jev.compare(state,Set.copyOf(ids));
            if(result.judgments().size()!=1 || !ids.contains(result.judgments().get(0).choice()) && !"uncertain".equals(result.judgments().get(0).choice()))throw new IllegalArgumentException("Unknown departure");
            var row=new JevReview();row.id=UUID.randomUUID().toString();row.owner=browser.id();row.created=now;row.expiresAt=now.plus(Duration.ofDays(30));row.model=result.model();row.judgments=json.writeValueAsString(result.judgments());row.evidence=json.writeValueAsString(evidence);reviews.save(row);
            flash.addFlashAttribute("notice","Jev compared the departures that fit your limits. Match its option number to the departure details below. No trip was selected or started; refresh the planner before leaving.");
        } catch(Exception e) {flash.addFlashAttribute("notice","Jev could not compare these departures. Your regular commute planner continues to work.");org.slf4j.LoggerFactory.getLogger(getClass()).warn("Jev comparison unavailable ({})",e.getClass().getSimpleName());}
        return "redirect:/assistant";
    }
    private static Map<String,Object> comparisonOption(String id,String routes,Instant leave,Instant arrival,long firstWait,long transferWait,int transferWalk,String source) {
        return Map.of("id",id,"routes",routes,"leave",leave.toString(),"destination_arrival",arrival.toString(),"first_stop_wait_minutes",firstWait,"transfer_wait_minutes",transferWait,"transfer_walk_minutes",transferWalk,"source",source);
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000,initialDelay=3600000) public void expireReviews() { reviews.deleteByExpiresAtBefore(clock.instant()); }
    static String clip(String text,int limit) {return text==null?"":text.substring(0,Math.min(limit,text.length()));}
    @PostMapping("/assistant/feedback") public String feedback(@RequestParam String id,@RequestParam String feedback) {
        if(!Set.of("helpful","not_helpful","incorrect","uncertain").contains(feedback))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        reviews.findByIdAndOwner(id,browser.id()).ifPresent(r->{r.feedback=feedback;reviews.save(r);});return "redirect:/assistant";
    }
    @PostMapping("/assistant/clear") public String clear() {reviews.deleteByOwner(browser.id());return "redirect:/assistant";}
}
