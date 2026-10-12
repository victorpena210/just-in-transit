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

@Controller
public class HomeController {
    private final ScheduleStore schedules;
    private final RealtimeStore realtime;
    private final CommutePlanner planner;
    private final CommuteRepository saved;
    private final AlertStore alerts;
    private final MapService maps;
    public HomeController(ScheduleStore schedules,RealtimeStore realtime,CommutePlanner planner,CommuteRepository saved,AlertStore alerts,MapService maps) {
        this.schedules=schedules;this.realtime=realtime;this.planner=planner;this.saved=saved;this.alerts=alerts;this.maps=maps;
    }
    @GetMapping("/") public String home(Model model) {
        LocalDate day=LocalDate.now(ZONE);
        var previous=saved.findById(1L);
        LocalTime deadline=previous.map(s->s.arriveBy).orElse(LocalTime.of(9,0));
        if(!LocalTime.now(ZONE).isBefore(deadline))day=day.plusDays(1);
        var form=previous.isPresent()?previous.get().form(day):new CommuteForm("","Six Flags Fiesta Texas — employee entrance",day,LocalTime.of(9,0),8,3,10,3,5,5,"83463");
        model.addAttribute("commute",form);
        return render(form,model,false);
    }
    @PostMapping("/settings") public String settings(@ModelAttribute("commute") CommuteForm form,BindingResult errors,Model model) {
        return render(form,model,false);
    }
    @PostMapping("/plan") public String plan(@Valid @ModelAttribute("commute") CommuteForm form,BindingResult errors,Model model) {
        validateStops(form,errors);
        return render(form,model,!errors.hasErrors());
    }
    @PostMapping("/save") public String save(@Valid @ModelAttribute("commute") CommuteForm form,BindingResult errors,Model model) {
        validateStops(form,errors);
        if(!errors.hasErrors()) {
            saved.save(SavedCommute.from(form));
            model.addAttribute("notice","Commute saved on this computer.");
        }
        return render(form,model,!errors.hasErrors());
    }
    @PostMapping("/forget") public String forget() { saved.deleteById(1L);maps.clearCache();return "redirect:/"; }
    private void validateStops(CommuteForm form,BindingResult errors) {
        if(errors.hasErrors())return;
        Schedule schedule=schedules.get();if(schedule==null)return;
        for(String kind:java.util.List.of("origin","destination")) {
            String id=kind.equals("origin")?form.originStop():form.destinationStop();
            var stop=CorridorStops.eligible(schedule,kind,form.date()).stream().filter(v->v.id().equals(id)).findFirst();
            if(stop.isEmpty())errors.rejectValue(kind+"Stop","unsupported","Choose a stop serving the 24 → 93 transfer on this date.");
            else if("address".equals(form.locationMode())) {
                double lat=kind.equals("origin")?form.originLat():form.destinationLat(),lon=kind.equals("origin")?form.originLon():form.destinationLon();
                if(!MapService.inArea(lat,lon) || CorridorStops.meters(lat,lon,stop.get().lat(),stop.get().lon())>2000)
                    errors.rejectValue(kind+"Stop","outside","The selected stop is outside the 2 km address search area. Find nearby stops again.");
            }
        }
    }
    private String render(CommuteForm form,Model model,boolean calculate) {
        Schedule schedule=schedules.get();
        RealtimeStore.Snapshot live=realtime.get();
        Instant now=Instant.now();
        model.addAttribute("scheduleStatus",schedules.message());
        model.addAttribute("liveStatus",realtime.message());
        model.addAttribute("feedUpdated",live.updatedDisplay());
        model.addAttribute("feedFresh",live.fresh(now));
        model.addAttribute("checkedAt",time(now));
        model.addAttribute("board",planner.board(schedule,live,now,form.originStop(),form.destinationStop()));
        LocalDate day=form.date()==null?now.atZone(ZONE).toLocalDate():form.date();
        model.addAttribute("originStops",stopChoices(schedule,"origin",day,form.originStop()));
        model.addAttribute("destinationStops",stopChoices(schedule,"destination",day,form.destinationStop()));
        model.addAttribute("originStopName",stopName(schedule,form.originStop(),"Houston & Mittman"));
        model.addAttribute("destinationStopName",stopName(schedule,form.destinationStop(),"Selected 93 stop"));
        model.addAttribute("hasSaved",saved.existsById(1L));
        Result result=calculate?planner.plan(form,schedule,live,now):null;
        model.addAttribute("result",result);
        var advisory=alerts.get();
        model.addAttribute("alerts",advisory.relevant(AlertStore.contexts(form,schedule,result,now)));
        model.addAttribute("alertStatus",alerts.status(now));
        model.addAttribute("alertsConfirmed",alerts.confirmed(now));
        model.addAttribute("alertUpdated",advisory.updatedDisplay());
        model.addAttribute("homeMap",maps(form.origin(),stopCoordinates(schedule,form.originStop(),"Houston & Mittman, San Antonio")));
        model.addAttribute("transferMap",maps(stopCoordinates(schedule,"76779","Martin & Leona, San Antonio"),stopCoordinates(schedule,"76773","Martin Opposite Leona, San Antonio")));
        String lastStop="Six Flags Fiesta Texas";
        if(schedule!=null && form.destinationStop()!=null && schedule.stops().containsKey(form.destinationStop())) {
            var stop=schedule.stops().get(form.destinationStop());lastStop=stop.lat()+","+stop.lon();
        }
        model.addAttribute("workMap",maps(lastStop,form.destination()));
        return "home";
    }
    private static java.util.List<Stop> stopChoices(Schedule schedule,String kind,LocalDate day,String selected) {
        var choices=new java.util.ArrayList<>(CorridorStops.eligible(schedule,kind,day));
        if(selected!=null && choices.stream().noneMatch(s->s.id().equals(selected)))
            choices.add(0,new Stop(selected,stopName(schedule,selected,"Selected stop "+selected)+" · check service date",0,0));
        return choices;
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
