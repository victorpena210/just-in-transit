package com.victorpena.just_in_transit;

import jakarta.servlet.http.*;
import java.time.*;
import java.util.UUID;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

/** A random HttpOnly cookie identifies one browser's expiring server-side checkpoint. */
@Service
public class JourneyRecovery {
    static final String COOKIE="JIT_JOURNEY";
    private final JourneyCheckpointRepository records;
    @org.springframework.beans.factory.annotation.Autowired private BrowserIdentity browser;
    private final JsonMapper json=JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    public JourneyRecovery(JourneyCheckpointRepository records) { this.records=records; }
    private String id(HttpServletRequest request) {
        if(request.getCookies()!=null)for(var cookie:request.getCookies())
            if(COOKIE.equals(cookie.getName()) && cookie.getValue().matches("[a-f0-9-]{36}"))return cookie.getValue();
        return null;
    }
    public ActiveJourney.State load(HttpServletRequest request,Instant now) {
        String id=id(request);if(id==null)return null;
        var record=records.findById(id).orElse(null);
        if(record==null || record.owner!=null && !record.owner.equals(browser.id()))return null;
        if(!now.isBefore(record.expiresAt)) { records.deleteById(id);return null; }
        try {
            var state=json.readValue(record.payload,ActiveJourney.State.class);
            return state.expired(now)?null:state;
        } catch(RuntimeException e) { records.deleteById(id);return null; }
    }
    public void save(HttpServletRequest request,HttpServletResponse response,ActiveJourney.State state,Instant now) {
        records.deleteByExpiresAtBefore(now);
        String old=id(request);
        if(old!=null) {
            var existing=records.findById(old).orElse(null);
            if(existing!=null && existing.owner!=null && !existing.owner.equals(browser.id()))old=null;
            else records.deleteById(old);
        }
        if(state==null) { cookie(response,request,"",0);return; }
        var record=new JourneyCheckpoint();record.id=old==null?UUID.randomUUID().toString():old;
        record.owner=browser.id();record.payload=json.writeValueAsString(state);record.expiresAt=state.started().plus(Duration.ofHours(18));records.save(record);
        cookie(response,request,record.id,Math.max(0,Duration.between(now,record.expiresAt).getSeconds()));
    }
    private void cookie(HttpServletResponse response,HttpServletRequest request,String value,long seconds) {
        response.addHeader("Set-Cookie",ResponseCookie.from(COOKIE,value).httpOnly(true).secure(request.isSecure())
            .sameSite("Lax").path("/").maxAge(seconds).build().toString());
    }
}
