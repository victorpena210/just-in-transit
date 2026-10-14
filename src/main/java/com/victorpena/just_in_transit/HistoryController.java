package com.victorpena.just_in_transit;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.*;
@Controller
public class HistoryController {
    private final TripReportRepository reports;
    public HistoryController(TripReportRepository reports) { this.reports=reports; }
    @GetMapping("/history") public String history(Model model,@RequestParam(defaultValue="0") int page) {
        var results=reports.findAll(PageRequest.of(Math.max(0,Math.min(page,100000)),25,Sort.by(Sort.Direction.DESC,"actualArrival")));
        model.addAttribute("reports",results.getContent());model.addAttribute("page",results);return "history";
    }
    @PostMapping("/history/delete") public String delete(@RequestParam String id) { reports.deleteById(id);return "redirect:/history"; }
    @PostMapping("/history/clear") public String clear() { reports.deleteAll();return "redirect:/history"; }
}
