package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.ActiveJourney.*;
import static com.victorpena.just_in_transit.TransitModels.*;
import jakarta.servlet.http.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Checkpoint, opt-in report and retry receipt commit together; sessions are updated only after commit. */
@Service
public class JourneyActions {
    private final JourneyRecovery recovery;private final JourneyReceiptRepository receipts;private final BrowserIdentity browser;
    private final ScheduleStore schedules;private final RealtimeStore realtime;private final RiderTracking tracking;private final TripHistory history;private final Clock clock;
    public JourneyActions(JourneyRecovery recovery,JourneyReceiptRepository receipts,BrowserIdentity browser,ScheduleStore schedules,RealtimeStore realtime,RiderTracking tracking,TripHistory history,Clock clock) {
        this.recovery=recovery;this.receipts=receipts;this.browser=browser;this.schedules=schedules;this.realtime=realtime;this.tracking=tracking;this.history=history;this.clock=clock;
    }
    public record Update(@NotBlank @Pattern(regexp="[a-fA-F0-9-]{36}") String eventId,@Size(max=80) String journeyId,@Size(max=80) String revision,
        @NotBlank @Pattern(regexp="advance|at24|missed24|missed93|replace93|undo|end|selectBus|destination|history") String action,
        @NotNull Instant recordedAt,@Size(max=40) String expectedPhase,@Pattern(regexp="24|93") String route,@Size(max=64) String vehicleId,
        Boolean driverConfirmed,Boolean keepHistory,@Pattern(regexp="work|home") String corridor,@Size(max=64) String destinationStop,@Min(0) @Max(120) Integer walkToWork) {
        public Update { if(driverConfirmed==null)driverConfirmed=false;if(keepHistory==null)keepHistory=false; }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000,initialDelay=3600000)
    public void expireReceipts() { receipts.deleteByExpiresAtBefore(clock.instant()); }
    @Transactional
    public State start(HttpServletRequest req,HttpServletResponse res,State state) {
        recovery.saveChecked(req,res,null,state,clock.instant());history.update(state,browser.id(),false);return state;
    }
    @Transactional
    public State apply(HttpServletRequest req,HttpServletResponse res,Update update,CommuteForm fallback) {
        Instant now=clock.instant();String key=browser.id()+":"+update.eventId();
        String fingerprint=BrowserIdentity.hash(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(update));
        var receipt=receipts.findById(key).orElse(null);
        if(receipt!=null) {
            if(!Objects.equals(receipt.fingerprint,fingerprint))throw new JourneyConflict("This update ID was already used. Review your saved trip.");
            return recovery.load(req,now);
        }
        State before=recovery.load(req,now);
        if(!Objects.equals(update.revision(),before==null?null:before.revision()) || !Objects.equals(update.journeyId(),before==null?null:before.facts().id()))
            throw new JourneyConflict("Your journey changed in another tab. This update is kept on your phone for review.");
        if(before!=null && update.expectedPhase()!=null && !before.phase().name().equals(update.expectedPhase()))throw new JourneyConflict("Your trip is at a different step. Review your saved status.");
        Instant at=update.recordedAt();
        if(at.isAfter(now.plusSeconds(60)) || at.isBefore(now.minus(Duration.ofHours(18))) || before!=null && at.isBefore(before.changed()))
            throw new JourneyConflict("The update time does not fit this trip. Review the saved status before continuing.");
        State state=tracking.observe(before,now);State next;
        if("selectBus".equals(update.action())) {
            var f=state==null?fallback:state.form();
            if(f==null)throw new IllegalArgumentException("Save your commute or find a departure first, then select your bus.");
            f=f.withTime(now.atZone(ZONE).toLocalDate(),f.arriveBy(),f.planMode(),f.startMode(),f.firstTrip());
            next=tracking.select(state,f,update.route(),update.vehicleId(),update.driverConfirmed(),update.keepHistory(),at,now);
        } else {
            if(state==null)throw new JourneyConflict("There is no active commute. Review your saved status before starting another.");
            var schedule=schedules.get();var live=realtime.get();
            next=switch(update.action()) {
                case "advance" -> ActiveJourney.advance(state,schedule,live,at);
                case "at24" -> ActiveJourney.at24(state,at);
                case "missed24" -> ActiveJourney.missed24(state,schedule,live,now);
                case "missed93" -> ActiveJourney.missed93(state,schedule,live,now);
                case "replace93" -> ActiveJourney.replaceSecond(state,schedule,live,now);
                case "undo" -> ActiveJourney.undo(state,at);
                case "end" -> null;
                case "history" -> new State(UUID.randomUUID().toString(),state.form(),state.option(),state.phase(),state.started(),at,state.missed(),state.needs93(),state.needs24(),
                    new Facts(state.facts().id(),state.facts().plannedDeparture(),state.facts().plannedArrival(),update.keepHistory(),state.facts().confirmations(),state.facts().predictions(),state.facts().predictionSources()),state.snapshot(),state.ride());
                case "destination" -> {
                    if(state.phase().ordinal()>=Phase.WALKING_TO_DESTINATION.ordinal())throw new IllegalArgumentException("Your bus ride has ended. Start a new trip to change its destination.");
                    var stop=schedule==null?null:schedule.stops().get(update.destinationStop());
                    if(stop==null || update.walkToWork()==null || schedule.trips().stream().noneMatch(t->t.route().equals(state.form().route().secondRoute()) && t.stops().stream().anyMatch(s->s.id().equals(stop.id()) && s.dropoff())))
                        throw new IllegalArgumentException("Choose a stop served by your final bus and enter the final walking minutes.");
                    var f=state.form().withDestination(stop.id(),stop.name(),update.walkToWork());
                    yield tracking.observe(new State(UUID.randomUUID().toString(),f,state.option(),state.phase(),state.started(),at,state.missed(),state.needs93(),state.needs24(),state.facts().confirm("destinationChanged",at),state.snapshot(),state.ride()),now);
                }
                default -> throw new IllegalArgumentException("Unknown journey update.");
            };
        }
        recovery.saveChecked(req,res,update.revision(),next,now);
        history.update(next==null?before:next,browser.id(),next==null);
        if("history".equals(update.action()) && !update.keepHistory())history.remove(before.facts().id(),browser.id());
        var accepted=new JourneyReceipt();accepted.id=key;accepted.owner=browser.id();accepted.fingerprint=fingerprint;accepted.expiresAt=now.plus(Duration.ofDays(2));receipts.saveAndFlush(accepted);
        return next;
    }
}
