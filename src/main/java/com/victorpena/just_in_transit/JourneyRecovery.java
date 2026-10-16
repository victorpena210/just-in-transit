package com.victorpena.just_in_transit;

import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

/** One durable active journey per browser credential. Never delete a checkpoint before replacing it. */
@Service
public class JourneyRecovery {
    static final String COOKIE="JIT_JOURNEY";
    private final JourneyCheckpointRepository records;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired private BrowserIdentity browser;
    private final JsonMapper json=JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    public JourneyRecovery(JourneyCheckpointRepository records,Clock clock) { this.records=records;this.clock=clock; }
    private String key() { return UUID.nameUUIDFromBytes(("journey:"+browser.id()).getBytes(StandardCharsets.UTF_8)).toString(); }
    private String cookieId(HttpServletRequest request) {
        if(request.getCookies()!=null)for(var c:request.getCookies())if(COOKIE.equals(c.getName()) && c.getValue().matches("[a-f0-9-]{36}"))return c.getValue();
        return null;
    }
    private JourneyCheckpoint record(HttpServletRequest request) {
        var row=records.findById(key()).orElse(null);
        if(row==null && cookieId(request)!=null)row=records.findById(cookieId(request)).orElse(null);
        if(row!=null && Objects.equals(row.owner,browser.id()))return row;
        return records.findFirstByOwnerOrderByExpiresAtDesc(browser.id()).orElse(null);
    }
    private ActiveJourney.State decode(JourneyCheckpoint row,Instant now) {
        if(row==null || !now.isBefore(row.expiresAt))return null;
        // A malformed checkpoint is retained for diagnosis, never silently deleted.
        var state=json.readValue(row.payload,ActiveJourney.State.class);
        return state.expired(now)?null:state;
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000,initialDelay=3600000)
    public void expireCheckpoints() { records.deleteByExpiresAtBefore(clock.instant()); }
    @Transactional(readOnly=true)
    public ActiveJourney.State load(HttpServletRequest request,Instant now) { return decode(record(request),now); }
    @Transactional
    public void save(HttpServletRequest request,HttpServletResponse response,ActiveJourney.State state,Instant now) {
        write(request,response,state,now);
    }
    @Transactional
    public void saveChecked(HttpServletRequest request,HttpServletResponse response,String expected,ActiveJourney.State state,Instant now) {
        var current=decode(record(request),now);
        if(!Objects.equals(expected,current==null?null:current.revision()))
            throw new JourneyConflict("Your journey changed in another tab. Review the current step before applying this update.");
        write(request,response,state,now);
    }
    private void write(HttpServletRequest request,HttpServletResponse response,ActiveJourney.State state,Instant now) {
        var old=record(request);
        if(state==null) {
            if(old!=null)records.delete(old);
            records.flush();cookie(response,request,"",0);return;
        }
        String payload=json.writeValueAsString(state); // Serialize before changing any record.
        var row=old==null?new JourneyCheckpoint():old;
        if(old==null)row.id=key();
        row.owner=browser.id();row.payload=payload;row.expiresAt=state.started().plus(Duration.ofHours(18));
        records.saveAndFlush(row);
        cookie(response,request,row.id,Math.max(0,Duration.between(now,row.expiresAt).getSeconds()));
    }
    private void cookie(HttpServletResponse response,HttpServletRequest request,String value,long seconds) {
        response.addHeader("Set-Cookie",ResponseCookie.from(COOKIE,value).httpOnly(true).secure(request.isSecure()).sameSite("Lax").path("/").maxAge(seconds).build().toString());
    }
}
