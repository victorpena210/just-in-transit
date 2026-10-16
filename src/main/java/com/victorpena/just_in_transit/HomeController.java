package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class HomeController {
    private final ScheduleStore schedules;
    private final RealtimeStore realtime;
    private final CommutePlanner planner;
    private final BrowserCommuteStore saved;
    private final AlertStore alerts;
    private final MapService maps;
    private final JourneySession journeys;
    @org.springframework.beans.factory.annotation.Autowired private JourneyActions journeyActions;
    @org.springframework.beans.factory.annotation.Autowired private RiderTracking riderTracking;
    private final Clock clock;
    private final AccessSettings access;
    private final JourneyRecovery recovery;
    private final TripHistory history;
    @org.springframework.beans.factory.annotation.Autowired private BrowserIdentity browser;
    @org.springframework.beans.factory.annotation.Autowired private CommuteNotifications notifications;
    @org.springframework.beans.factory.annotation.Autowired private JevClient jev;
    @org.springframework.beans.factory.annotation.Autowired private jakarta.validation.Validator validator;
    @org.springframework.beans.factory.annotation.Autowired private AlternativePlanner alternatives;
    @org.springframework.beans.factory.annotation.Value("${maps.tile-url:https://tile.openstreetmap.org/{z}/{x}/{y}.png}")
    private String tileUrl;
    @org.springframework.beans.factory.annotation.Value("${maps.tile-credit:}") private String tileCredit;
    public HomeController(ScheduleStore schedules,RealtimeStore realtime,CommutePlanner planner,BrowserCommuteStore saved,AlertStore alerts,MapService maps,JourneySession journeys,Clock clock,AccessSettings access,JourneyRecovery recovery,TripHistory history) {
        this.schedules=schedules;this.realtime=realtime;this.planner=planner;this.saved=saved;this.alerts=alerts;this.maps=maps;this.journeys=journeys;this.clock=clock;this.access=access;this.recovery=recovery;this.history=history;
    }
    @GetMapping("/") public String home(Model model,jakarta.servlet.http.HttpServletRequest request,@RequestParam(defaultValue="") String corridor) {
        var active=journeys.current(clock.instant());
        if(active!=null) {
            model.addAttribute("commute",active.form());
            return render(active.form(),model,false);
        }
        if(model.asMap().get("savedPlan") instanceof CommuteForm justSaved) {
            model.addAttribute("commute",justSaved);
            return render(justSaved,model,true);
        }
        model.addAttribute("resumeJourney",recovery.load(request,clock.instant()));
        if(model.asMap().get("commute") instanceof CommuteForm submitted)return render(submitted,model,true);
        LocalDate day=LocalDate.now(clock.withZone(ZONE));
        var last=journeys.lastForm();
        String key=corridor.isBlank()?(last==null?"work":last.corridor()):corridor;
        if(!java.util.Set.of("work","home").contains(key))return "redirect:/";
        var previous=saved.findById(Corridor.of(key).savedId());
        var form=last!=null && corridor.isBlank()?last.withTime(day,last.arriveBy(),last.planMode(),day.equals(last.date())?last.startMode():"home",day.equals(last.date())?last.firstTrip():""):
            previous.isPresent()?previous.get().form(day):defaultForm(key,day);
        model.addAttribute("commute",form);
        return render(form,model,previous.isPresent() || last!=null && corridor.isBlank());
    }
    @PostMapping("/settings") public String settings(@ModelAttribute("commute") CommuteForm form,BindingResult errors,Model model) {
        if(journeys.current(clock.instant())!=null)return "redirect:/";
        return render(form,model,false);
    }
    @PostMapping("/plan") public String plan(@Valid @ModelAttribute("commute") CommuteForm form,BindingResult errors,Model model) {
        if(journeys.current(clock.instant())!=null)return "redirect:/";
        notifications.stop(browser.id());
        validateStops(form,errors);
        return render(form,model,!errors.hasErrors());
    }
    @PostMapping("/save") public String save(@Valid @ModelAttribute("commute") CommuteForm form,BindingResult errors,Model model,RedirectAttributes flash) {
        if(journeys.current(clock.instant())!=null)return "redirect:/";
        notifications.stop(browser.id());
        validateStops(form,errors);
        if(!errors.hasErrors()) {
            journeys.remember(form);
            try {
                saved.saveAndFlush(SavedCommute.from(form));
            } catch(org.springframework.dao.DataAccessException ex) {
                org.slf4j.LoggerFactory.getLogger(HomeController.class).error("Could not save commute settings",ex);
                errors.reject("save.failed","Your commute could not be saved. Your entries are still here; please try again.");
                return render(form,model,false);
            }
            // Commit first, then render on GET: refresh never repeats a save.
            flash.addFlashAttribute("notice","Commute saved. Your walking times and wait preferences are ready to reuse.");
            flash.addFlashAttribute("savedPlan",form);
            return "redirect:/";
        }
        return render(form,model,!errors.hasErrors());
    }
    public record DailyForm(@jakarta.validation.constraints.NotNull @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date,
                            @jakarta.validation.constraints.NotNull @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.TIME) LocalTime arriveBy,
                            @jakarta.validation.constraints.Pattern(regexp="work|home") String corridor,
                            @jakarta.validation.constraints.Pattern(regexp="arrive|depart") String planMode,
                            @jakarta.validation.constraints.Pattern(regexp="home|stop|onboard") String startMode,
                            @jakarta.validation.constraints.Size(max=200) String firstTrip,
                            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(60) Integer arrivalBuffer,
                            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(360) Integer maxEarlyArrival,
                            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(30) Integer boardingBuffer,
                            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(120) Integer maxBoardingWait,
                            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(30) Integer transferBuffer,
                            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(120) Integer maxTransferWait) {
        public DailyForm { if(corridor==null)corridor="work";if(planMode==null)planMode="arrive";if(startMode==null)startMode="home";if(firstTrip==null)firstTrip=""; }
    }
    @PostMapping("/daily") public String daily(@Valid @ModelAttribute("daily") DailyForm daily,BindingResult dailyErrors,Model model,RedirectAttributes flash,@RequestParam(defaultValue="false") boolean refreshTrips,@RequestParam(defaultValue="false") boolean saveDefaults) {
        if(journeys.current(clock.instant())!=null)return "redirect:/";
        notifications.stop(browser.id());
        var commute=saved.findById(Corridor.of(daily.corridor()).savedId()).orElse(null);
        if(commute==null || dailyErrors.hasErrors()) { flash.addFlashAttribute("notice",commute==null?"Save your commute first.":"Choose a valid travel date and clock-in time.");return "redirect:/"; }
        var form=commute.form(daily.date()).withTime(daily.date(),daily.arriveBy(),daily.planMode(),daily.startMode(),daily.firstTrip());
        form=form.withArrivalWindow(daily.arrivalBuffer()==null?form.arrivalBuffer():daily.arrivalBuffer(),daily.maxEarlyArrival()==null?form.maxEarlyArrival():daily.maxEarlyArrival())
            .withWaitWindows(daily.boardingBuffer()==null?form.boardingBuffer():daily.boardingBuffer(),daily.maxBoardingWait()==null?form.maxBoardingWait():daily.maxBoardingWait(),
                daily.transferBuffer()==null?form.transferBuffer():daily.transferBuffer(),daily.maxTransferWait()==null?form.maxTransferWait():daily.maxTransferWait());
        if(refreshTrips) { model.addAttribute("commute",form);return render(form,model,false); }
        var errors=new org.springframework.validation.BeanPropertyBindingResult(form,"commute");
        for(var violation:validator.validate(form))errors.reject("preferences",violation.getMessage());
        validateStops(form,errors);
        model.addAttribute("commute",form);model.addAttribute(BindingResult.MODEL_KEY_PREFIX+"commute",errors);
        if(saveDefaults && !errors.hasErrors()) {
            saved.saveAndFlush(SavedCommute.from(form.withJourney("home","")));
            model.addAttribute("notice","Your usual timing and wait preferences are saved.");
        }
        // Today's deadline stays temporary unless Save timing as my defaults is explicitly chosen.
        return render(form,model,!errors.hasErrors());
    }
    @PostMapping("/forget") public String forget(@RequestParam(defaultValue="work") String corridor,jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) {
        notifications.stop(browser.id());saved.deleteById(Corridor.of(corridor).savedId());maps.clearCache();journeys.clear();recovery.save(request,response,null,clock.instant());return "redirect:/?corridor="+Corridor.of(corridor).key();
    }
    @GetMapping("/login") public String login() { return access.enabled()?"login":"redirect:/"; }
    @PostMapping("/journey/start") public String start(@RequestParam("token") String token,@RequestParam("option") int index,@RequestParam(defaultValue="false") boolean keepHistory,RedirectAttributes flash,jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) {
        try { var state=journeys.proposeStart(token,index,schedules.get(),realtime.get(),clock.instant(),keepHistory);journeys.restore(journeyActions.start(request,response,state));updateReminders(); }
        catch(IllegalArgumentException e) { flash.addFlashAttribute("notice",e.getMessage()); }
        return "redirect:/";
    }
    @PostMapping("/journey/action") public String progress(@RequestParam("revision") String revision,@RequestParam("action") String action,RedirectAttributes flash,jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) {
        try {
            var before=journeys.current(clock.instant());
            var update=new JourneyActions.Update(java.util.UUID.randomUUID().toString(),before==null?null:before.facts().id(),revision,action,clock.instant(),before==null?null:before.phase().name(),null,null,false,false,null,null,null);
            var next=journeyActions.apply(request,response,update,journeys.lastForm());journeys.restore(next);updateReminders(); }
        catch(IllegalArgumentException e) { flash.addFlashAttribute("notice",e.getMessage()); }
        return "redirect:/";
    }
    @PostMapping("/journey/resume") public String resume(@RequestParam String action,RedirectAttributes flash,
        jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) {
        try {
            if("resume".equals(action)) { journeys.resume(recovery.load(request,clock.instant()),clock.instant());notifications.update(browser.id(),journeys.current(clock.instant())); }
            else if("discard".equals(action)) { recovery.save(request,response,null,clock.instant());notifications.stop(browser.id()); }
            else throw new IllegalArgumentException("Unknown recovery action.");
        } catch(IllegalArgumentException e) { flash.addFlashAttribute("notice",e.getMessage()); }
        return "redirect:/";
    }
    private void updateReminders() {
        try { notifications.update(browser.id(),journeys.current(clock.instant())); }
        catch(RuntimeException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Trip saved; optional reminders unavailable",e); }
    }
    private void validateStops(CommuteForm form,BindingResult errors) {
        if(errors.hasErrors())return;
        Schedule schedule=schedules.get();if(schedule==null)return;
        if(form.onboard() && planner.boardingChoices(form,schedule,realtime.get(),clock.instant()).stream().noneMatch(c->c.key().equals(form.firstTrip())))
            errors.rejectValue("firstTrip","required","Select the bus you actually boarded. Refresh the trip list if your bus is missing.");
        if(!"home".equals(form.startMode()) && !form.date().equals(clock.instant().atZone(ZONE).toLocalDate()))
            errors.rejectValue("startMode","today","At-stop and already-on-board starts are for today. Choose At starting point for a future date.");
        for(String kind:java.util.List.of("origin","destination")) {
            String id=kind.equals("origin")?form.originStop():form.destinationStop();
            var stop=CorridorStops.eligible(schedule,kind,form.date(),form.route()).stream().filter(v->v.id().equals(id)).findFirst();
            if(stop.isEmpty())errors.rejectValue(kind+"Stop","unsupported","Choose a stop serving the "+form.route().routes()+" transfer on this date.");
            else if(form.usesAddress(kind)) {
                double lat=kind.equals("origin")?form.originLat():form.destinationLat(),lon=kind.equals("origin")?form.originLon():form.destinationLon();
                if(!MapService.inArea(lat,lon) || CorridorStops.meters(lat,lon,stop.get().lat(),stop.get().lon())>2000)
                    errors.rejectValue(kind+"Stop","outside","The selected stop is outside the 2 km address search area. Find nearby stops again.");
            }
        }
    }
    private String render(CommuteForm form,Model model,boolean calculate) {
        // Record constructor binding can leave a null-target BindingResult after a type mismatch.
        // Keep the supplied form and its rejected values together so Thymeleaf can render errors.
        var original=model.asMap().get(BindingResult.MODEL_KEY_PREFIX+"commute");
        model.addAttribute("commute",form);
        if(original instanceof BindingResult binding && binding.getTarget()!=form) {
            var repaired=new org.springframework.validation.BeanPropertyBindingResult(form,"commute");
            repaired.addAllErrors(binding);
            model.addAttribute(BindingResult.MODEL_KEY_PREFIX+"commute",repaired);
        }
        Schedule schedule=schedules.get();
        LiveSnapshot live=realtime.get();
        Instant now=clock.instant();
        var active=journeys.current(now);
        var journey=active==null?null:ActiveJourney.view(active,schedule,live,now);
        model.addAttribute("journey",journey);
        model.addAttribute("rideStatus",riderTracking.message(active,now));
        model.addAttribute("browserScope",browser.id());
        model.addAttribute("guidance",journey==null?null:JourneyGuidance.from(journey,schedule,live,now));
        model.addAttribute("route",form.route());
        model.addAttribute("boardingChoices",planner.boardingChoices(form,schedule,live,now));
        model.addAttribute("mapTileUrl",tileUrl);
        model.addAttribute("mapTileCredit",tileCredit);
        model.addAttribute("protectedAccess",access.enabled());
        model.addAttribute("jevAvailable",jev.available());
        model.addAttribute("scheduleStatus",schedules.message());
        model.addAttribute("liveStatus",realtime.message());
        model.addAttribute("feedUpdated",live.updatedDisplay());
        model.addAttribute("feedFresh",live.fresh(now));
        model.addAttribute("checkedAt",time(now));
        model.addAttribute("board",planner.board(schedule,live,now,form.originStop(),form.destinationStop(),form.route()));
        LocalDate day=form.date()==null?now.atZone(ZONE).toLocalDate():form.date();
        model.addAttribute("originStops",stopChoices(schedule,"origin",day,form.originStop(),form.route()));
        model.addAttribute("destinationStops",stopChoices(schedule,"destination",day,form.destinationStop(),form.route()));
        model.addAttribute("originStopName",stopName(schedule,form.originStop(),"Houston & Mittman"));
        model.addAttribute("destinationStopName",stopName(schedule,form.destinationStop(),"Selected 93 stop"));
        model.addAttribute("hasSaved",saved.existsById(form.route().savedId()));
        model.addAttribute("daily",new DailyForm(form.date(),form.arriveBy(),form.corridor(),form.planMode(),form.startMode(),form.firstTrip(),form.arrivalBuffer(),form.maxEarlyArrival(),form.boardingBuffer(),form.maxBoardingWait(),form.transferBuffer(),form.maxTransferWait()));
        model.addAttribute("renderedAt",now);
        if(calculate) {
            var binding=new org.springframework.validation.BeanPropertyBindingResult(form,"commute");
            validateStops(form,binding);
            if(binding.hasErrors()) { model.addAttribute(BindingResult.MODEL_KEY_PREFIX+"commute",binding);calculate=false; }
        }
        Result result=calculate?planner.plan(form,schedule,live,now):null;
        model.addAttribute("result",result);
        var otherRoutes=result==null || active!=null?java.util.List.<AlternativePlanner.Choice>of():alternatives.forCommute(schedule,live,form,now);
        model.addAttribute("otherRoutes",otherRoutes);
        var otherBest=otherRoutes.stream().filter(AlternativePlanner.Choice::fits).findFirst().orElse(null);
        var corridorBest=result==null || result.options().isEmpty()?null:result.options().get(0);
        boolean highlightOther=otherBest!=null && (corridorBest==null || !corridorBest.meetsBuffers()
            || form.departing() && otherBest.arrival().isBefore(corridorBest.atWork())
            || !form.departing() && form.preference().equals("latest") && otherBest.leave().isAfter(corridorBest.leaveHome()));
        model.addAttribute("headlineAlternative",highlightOther?otherBest:null);
        if(result!=null)model.addAttribute("planToken",journeys.offer(form,result,now));
        model.addAttribute("canSelectBus",active!=null || journeys.lastForm()!=null || saved.existsById(form.route().savedId()));
        model.addAttribute("canStart",form.date()!=null && form.date().equals(now.atZone(ZONE).toLocalDate()));
        boolean invalid=model.asMap().get(BindingResult.MODEL_KEY_PREFIX+"commute") instanceof BindingResult binding && binding.hasErrors();
        model.addAttribute("editOpen",invalid || result==null && active==null && !saved.existsById(form.route().savedId()));
        model.addAttribute("hasJourneyContent",active!=null || result!=null && !result.options().isEmpty());
        var advisory=alerts.get();
        var advisoryContexts=new java.util.ArrayList<>(AlertStore.contexts(form,schedule,journey==null?result:new Result(java.util.List.of(journey.option()),"Active commute"),now));
        for(var choice:otherRoutes)for(var ride:choice.rides())advisoryContexts.add(AlertStore.context(schedule,
            new Leg(ride.trip().route(),ride.trip().id(),ride.board(),ride.alight(),ride.day()),ride.from().id(),ride.to().id()));
        var relevant=advisory.relevant(advisoryContexts);
        model.addAttribute("alerts",relevant);
        if(journey!=null && !journey.finished() && !relevant.isEmpty()) {
            var guidance=(JourneyGuidance)model.asMap().get("guidance");
            model.addAttribute("guidance",new JourneyGuidance(guidance.status()+" · check service advisory","tight",guidance.nextStep(),guidance.change(),guidance.backup(),guidance.canReplace()));
        }
        model.addAttribute("alertStatus",alerts.status(now));
        model.addAttribute("alertsConfirmed",alerts.confirmed(now));
        model.addAttribute("alertUpdated",advisory.updatedDisplay());
        model.addAttribute("homeMap",maps(form.usesAddress("origin") && form.originLat()!=null && form.originLon()!=null ? form.originLat()+","+form.originLon() : form.origin(),stopCoordinates(schedule,form.originStop(),"Houston & Mittman, San Antonio")));
        model.addAttribute("transferMap",maps(stopCoordinates(schedule,"76779","Martin & Leona, San Antonio"),stopCoordinates(schedule,"76773","Martin Opposite Leona, San Antonio")));
        String lastStop="Six Flags Fiesta Texas";
        if(schedule!=null && form.destinationStop()!=null && schedule.stops().containsKey(form.destinationStop())) {
            var stop=schedule.stops().get(form.destinationStop());lastStop=stop.lat()+","+stop.lon();
        }
        model.addAttribute("workMap",maps(lastStop,form.usesAddress("destination") && form.destinationLat()!=null && form.destinationLon()!=null ? form.destinationLat()+","+form.destinationLon() : form.destination()));
        return "home";
    }
    private static java.util.List<Stop> stopChoices(Schedule schedule,String kind,LocalDate day,String selected,Corridor corridor) {
        var choices=new java.util.ArrayList<>(CorridorStops.eligible(schedule,kind,day,corridor));
        if(selected!=null && choices.stream().noneMatch(s->s.id().equals(selected)))
            choices.add(0,new Stop(selected,stopName(schedule,selected,"Selected stop "+selected)+" · check service date",0,0));
        return choices;
    }
    private static CommuteForm defaultForm(String key,LocalDate day) {
        if("home".equals(key))return new CommuteForm("Work","Home",day,LocalTime.of(17,0),null,3,null,3,5,0,
            "81793","75229","manual",null,null,null,null,null,null,"home","depart","home","");
        return new CommuteForm("","Six Flags Fiesta Texas — employee entrance",day,LocalTime.of(9,0),8,3,10,3,5,5,"83463").withPreferences(15,30,5,"latest");
    }
    private static String stopName(Schedule schedule,String id,String fallback) {
        return schedule!=null && id!=null && schedule.stops().containsKey(id)?schedule.stops().get(id).name():fallback;
    }
    private static String stopCoordinates(Schedule schedule,String id,String fallback) {
        if(schedule==null || id==null || !schedule.stops().containsKey(id))return fallback;
        var s=schedule.stops().get(id);return s.lat()+","+s.lon();
    }
    private static String maps(String from,String to) {
        return "https://www.google.com/maps/dir/?api=1&travelmode=walking&origin="+encode(from)+"&destination="+encode(to);
    }
    private static String encode(String text) { return URLEncoder.encode(text==null?"":text,StandardCharsets.UTF_8); }
}
