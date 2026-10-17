package com.victorpena.just_in_transit;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.*;
@Controller
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="jit.personal.enabled", havingValue="false")
public class HistoryController {
    private final TripReportRepository reports;private final BrowserIdentity browser;
    public HistoryController(TripReportRepository reports,BrowserIdentity browser) { this.reports=reports;this.browser=browser; }
    @GetMapping("/history") public String history(Model model,@RequestParam(defaultValue="0") int page) {
        var results=reports.findByOwner(browser.id(),PageRequest.of(Math.max(0,Math.min(page,100000)),25,Sort.by(Sort.Direction.DESC,"actualArrival")));
        model.addAttribute("reports",results.getContent());model.addAttribute("page",results);return "history";
    }
    @PostMapping("/history/delete") public String delete(@RequestParam String id) { reports.deleteByIdAndOwner(id,browser.id());return "redirect:/history"; }
    @PostMapping("/history/note") public String note(@RequestParam String id,@RequestParam(defaultValue="") String note) {
        if(note.length()>1000)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Note too long");
        reports.findByIdAndOwner(id,browser.id()).ifPresent(r->{r.riderNote=note.strip();reports.save(r);});return "redirect:/history";
    }
    @GetMapping(value="/history.csv",produces="text/csv") @ResponseBody public org.springframework.http.ResponseEntity<String> csv() {
        var out=new StringBuilder("Date,Direction,Event,Initial estimate,Estimate source,Confirmed time,Changed buses,Rider note\r\n");
        int page=0;org.springframework.data.domain.Page<TripReport> batch;
        do { batch=reports.findByOwner(browser.id(),PageRequest.of(page++,100,Sort.by("travelDate").descending()));
            for(var r:batch)for(var t:r.timings())out.append(java.util.stream.Stream.of(String.valueOf(r.travelDate),r.title(),t.event(),r.display(t.estimated()),t.source(),r.display(t.confirmed()),String.valueOf(r.changedBuses),r.riderNote).map(HistoryController::cell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        } while(batch.hasNext() && page<100);
        return org.springframework.http.ResponseEntity.ok().header("Content-Disposition","attachment; filename=commute-comparisons.csv").body(out.toString());
    }
    static String cell(String value) { String s=value==null?"":value;if(s.matches("(?s)^[=+@\\t\\r\\n-].*"))s="'"+s;return "\""+s.replace("\"","\"\"")+"\""; }
    @PostMapping("/history/clear") public String clear() { reports.deleteByOwner(browser.id());return "redirect:/history"; }
}
