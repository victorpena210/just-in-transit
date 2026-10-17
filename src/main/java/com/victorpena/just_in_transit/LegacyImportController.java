package com.victorpena.just_in_transit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Legacy rows are never automatically handed to the first visitor of a public deployment. */
@Controller
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="jit.personal.enabled", havingValue="false")
public class LegacyImportController {
    private final CommuteRepository legacy;private final BrowserCommuteStore saved;private final TripReportRepository reports;private final BrowserIdentity browser;private final String code;
    private final ConcurrentHashMap<String,Instant> attempts=new ConcurrentHashMap<>();
    public LegacyImportController(CommuteRepository legacy,BrowserCommuteStore saved,TripReportRepository reports,BrowserIdentity browser,
        @Value("${jit.legacy-import-code:}") String code) { this.legacy=legacy;this.saved=saved;this.reports=reports;this.browser=browser;this.code=code; }
    @GetMapping("/restore") public String page(Model model) { model.addAttribute("configured",code.length()>=24);return "restore"; }
    @PostMapping("/restore") @Transactional public synchronized String restore(@RequestParam(defaultValue="") String code,RedirectAttributes flash) {
        Instant now=Instant.now();attempts.entrySet().removeIf(e->e.getValue().isBefore(now.minusSeconds(60)));
        Instant last=attempts.put(browser.id(),now);
        boolean valid=this.code.length()>=24 && code.length()<=200 && MessageDigest.isEqual(this.code.getBytes(StandardCharsets.UTF_8),code.getBytes(StandardCharsets.UTF_8));
        if(last!=null && last.isAfter(now.minusSeconds(10)) || !valid) { flash.addFlashAttribute("notice","Import code unavailable or incorrect. Wait a moment and try again.");return "redirect:/restore"; }
        // Single claim, recorded transactionally in the existing legacy rows. Never overwrite a browser's current setup.
        for(long id:new long[]{1,2}) {
            var row=legacy.findById(id).orElse(null);
            if(row!=null && row.legacyOwner==null) { if(!saved.existsById(id))saved.save(row);row.legacyOwner=browser.id();legacy.save(row); }
        }
        reports.claimLegacy(browser.id());flash.addFlashAttribute("notice","Available older settings and reports were restored to this browser. Existing settings were kept.");return "redirect:/";
    }
}
