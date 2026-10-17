package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@ConditionalOnProperty(name="jit.personal.enabled", havingValue="true", matchIfMissing=true)
public class PersonalCommuteController {
    private final ScheduleStore schedules;
    private final RealtimeStore realtime;
    private final PersonalCommuteRepository settings;
    private final Clock clock;
    private final AccessSettings access;
    private final BrowserIdentity browser;
    private final PersonalCommutePlanner planner = new PersonalCommutePlanner();

    public PersonalCommuteController(ScheduleStore schedules, RealtimeStore realtime,
            PersonalCommuteRepository settings, Clock clock, AccessSettings access, BrowserIdentity browser) {
        this.schedules=schedules; this.realtime=realtime; this.settings=settings; this.clock=clock; this.access=access; this.browser=browser;
    }
    // Mutable form preserves rejected values cleanly after malformed dates or numbers.
    public static class Form {
        @NotNull(message="Choose a travel date.")
        @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) private LocalDate date;
        @NotNull(message="Enter the time you need to reach Six Flags.")
        @DateTimeFormat(iso=DateTimeFormat.ISO.TIME) private LocalTime arriveBy;
        @NotNull @Min(0) @Max(60) private Integer finalWalk = 0;
        public LocalDate getDate() { return date; }
        public void setDate(LocalDate value) { date=value; }
        public LocalTime getArriveBy() { return arriveBy; }
        public void setArriveBy(LocalTime value) { arriveBy=value; }
        public Integer getFinalWalk() { return finalWalk; }
        public void setFinalWalk(Integer value) { finalWalk=value; }
    }
    @GetMapping("/") public String home(Model model) {
        LocalDate today = clock.instant().atZone(ZONE).toLocalDate();
        Form form = new Form(); form.setDate(today);
        var saved = settings.findById(browser.id()).orElse(null);
        if (saved != null) {
            form.setDate(saved.travelDate != null && saved.travelDate.isAfter(today) ? saved.travelDate : today);
            form.setArriveBy(saved.arriveBy); form.setFinalWalk(saved.finalWalk);
        }
        model.addAttribute("personal", form);
        return render(form, model, saved != null && saved.arriveBy != null, false);
    }
    @PostMapping("/commute") public String save(@Valid @ModelAttribute("personal") Form form,
            BindingResult errors, Model model) {
        validateDate(form, errors);
        if (errors.hasErrors()) return render(form, model, false, false);
        var saved = new PersonalCommuteSettings(); saved.id=browser.id();
        saved.travelDate=form.getDate(); saved.arriveBy=form.getArriveBy(); saved.finalWalk=form.getFinalWalk();
        settings.save(saved);
        return "redirect:/";
    }
    @GetMapping("/commute/preview") public String preview(@Valid @ModelAttribute("personal") Form form,
            BindingResult errors, Model model) {
        validateDate(form, errors);
        return render(form, model, !errors.hasErrors(), true);
    }
    @GetMapping("/login") public String login() { return access.enabled() ? "login" : "redirect:/"; }

    private void validateDate(Form form, BindingResult errors) {
        if (form.getDate()!=null && form.getDate().isBefore(clock.instant().atZone(ZONE).toLocalDate()))
            errors.rejectValue("date", "past", "Choose today or a future travel date.");
    }
    private String render(Form form, Model model, boolean calculate, boolean fragment) {
        Schedule schedule = calculate ? schedules.get() : null;
        LiveSnapshot live = calculate ? realtime.get() : LiveSnapshot.empty();
        Instant now = clock.instant();
        PersonalCommutePlanner.Result result = calculate ? planner.plan(
            new PersonalCommutePlanner.Request(form.getDate(), form.getArriveBy(), form.getFinalWalk()), schedule, live, now) : null;
        model.addAttribute("result", result);
        model.addAttribute("plan", result == null ? null : result.plan());
        model.addAttribute("checkedEpoch", now.toEpochMilli());
        model.addAttribute("checkedAt", time(now));
        model.addAttribute("today", now.atZone(ZONE).toLocalDate());
        model.addAttribute("scheduleStatus", calculate ? schedules.message() : "");
        model.addAttribute("liveStatus", calculate ? realtime.message() : "");
        model.addAttribute("feedUpdated", live.updatedDisplay());
        model.addAttribute("protectedAccess", access.enabled());
        return fragment ? "commute :: result" : "commute";
    }
}
