package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

@Component
@SessionScope
public class JourneySession {
    // Keep the offered buses for the browser session; every preview and start refreshes their timing.
    private static final long PLAN_LIFETIME_SECONDS=18*3600;
    private String choiceToken;
    private Instant plannedAt;
    private CommuteForm plannedForm;
    private List<Option> choices=List.of();
    private ActiveJourney.State active;
    public synchronized String offer(CommuteForm form,Result result,Instant now) {
        choiceToken=UUID.randomUUID().toString();plannedAt=now;plannedForm=form;choices=List.copyOf(result.options());
        return choiceToken;
    }
    public synchronized void remember(CommuteForm form) { plannedForm=form; }
    public synchronized CommuteForm lastForm() { return plannedForm; }
    public record MapChoice(CommuteForm form,Option option) { }
    public synchronized CommuteForm offeredForm(String token,Instant now) {
        return token!=null && Objects.equals(choiceToken,token) && plannedAt!=null && !now.isAfter(plannedAt.plusSeconds(PLAN_LIFETIME_SECONDS))?plannedForm:null;
    }
    public synchronized MapChoice mapChoice(String token,int index,Instant now) {
        if(token==null || !Objects.equals(choiceToken,token) || plannedAt==null || now.isAfter(plannedAt.plusSeconds(PLAN_LIFETIME_SECONDS))
            || index<0 || index>=choices.size())return null;
        return new MapChoice(plannedForm,choices.get(index));
    }
    public synchronized ActiveJourney.State current(Instant now) {
        if(active!=null && active.expired(now))active=null;
        return active;
    }
    public synchronized void start(String token,int index,Schedule schedule,LiveSnapshot live,Instant now) {
        start(token,index,schedule,live,now,false);
    }
    public synchronized void start(String token,int index,Schedule schedule,LiveSnapshot live,Instant now,boolean keepHistory) {
        if(current(now)!=null)throw new IllegalArgumentException("A commute is already active. Finish or end it before starting another.");
        if(!Objects.equals(choiceToken,token) || plannedAt==null || now.isAfter(plannedAt.plusSeconds(PLAN_LIFETIME_SECONDS)) || index<0 || index>=choices.size())
            throw new IllegalArgumentException("That departure plan has expired. Find your departure again.");
        active=ActiveJourney.start(plannedForm,choices.get(index),schedule,live,now,keepHistory);
        choices=List.of();choiceToken=null;
    }
    public synchronized void act(String revision,String action,Schedule schedule,LiveSnapshot live,Instant now) {
        var state=current(now);
        if(state==null)throw new IllegalArgumentException("There is no active commute in this browser session. Plan and start a new commute.");
        if(!state.revision().equals(revision))throw new IllegalArgumentException("Your journey changed in another tab. Review the current step and try again.");
        switch(action) {
            case "advance" -> active=ActiveJourney.advance(state,schedule,live,now);
            case "at24" -> active=ActiveJourney.at24(state,now);
            case "missed24" -> active=ActiveJourney.missed24(state,schedule,live,now);
            case "replace93" -> active=ActiveJourney.replaceSecond(state,schedule,live,now);
            case "missed93" -> active=ActiveJourney.missed93(state,schedule,live,now);
            case "undo" -> active=ActiveJourney.undo(state,now);
            case "end" -> { active=null;plannedForm=state.form().withJourney("home",""); }
            default -> throw new IllegalArgumentException("Unknown journey action.");
        }
    }
    public synchronized void resume(ActiveJourney.State state,Instant now) {
        if(current(now)!=null)throw new IllegalArgumentException("A commute is already active.");
        if(state==null || state.expired(now))throw new IllegalArgumentException("That saved journey expired. Plan a new commute.");
        active=state;
    }
    public synchronized void clear() { active=null;choices=List.of();choiceToken=null;plannedForm=null; }
}
