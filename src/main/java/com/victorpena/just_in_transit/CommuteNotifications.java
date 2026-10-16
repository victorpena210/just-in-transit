package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;import java.util.*;
import org.springframework.stereotype.Service;import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;import tools.jackson.databind.DeserializationFeature;

@Service public class CommuteNotifications {
    public record Watch(String generation,CommuteForm form,Option option,ActiveJourney.State journey,Instant created) { }
    public record Notice(String key,String title,String body) { }
    private final TripWatchRepository watches;private final PushSubscriptionRepository subscribers;private final PushTransport push;private final ScheduleStore schedules;private final RealtimeStore realtime;private final Clock clock;
    private final JsonMapper json=JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private String cursor="";
    public CommuteNotifications(TripWatchRepository watches,PushSubscriptionRepository subscribers,PushTransport push,ScheduleStore schedules,RealtimeStore realtime,Clock clock) { this.watches=watches;this.subscribers=subscribers;this.push=push;this.schedules=schedules;this.realtime=realtime;this.clock=clock; }
    public synchronized void watch(String owner,Watch data,Instant expires) {
        if(!subscribers.existsById(owner))return;
        var old=watches.findById(owner).orElse(null);var record=new TripWatch();record.owner=owner;record.revision=UUID.randomUUID().toString();record.payload=json.writeValueAsString(data);record.expiresAt=expires;
        if(old!=null && json.readValue(old.payload,Watch.class).generation().equals(data.generation()))record.sent=old.sent;
        watches.save(record);
    }
    public synchronized void update(String owner,ActiveJourney.State state) {
        if(state==null || state.phase()==ActiveJourney.Phase.ARRIVED) { stop(owner);return; }
        watch(owner,new Watch(state.facts().id(),state.form(),state.option(),state,state.started()),state.started().plus(Duration.ofHours(18)));
    }
    public synchronized void stop(String owner) { watches.deleteById(owner); }
    static List<Notice> notices(Watch w,Option current,ActiveJourney.View active,LiveSnapshot live,Instant now) {
        var list=new ArrayList<Notice>();var f=w.form();
        if(active==null && f.startMode().equals("home")) {
            long seconds=Duration.between(now,current.leaveHome()).getSeconds();
            String basis=current.first().board().source();
            if(seconds>60 && seconds<=300)list.add(new Notice("leave-soon","Leave in about "+Math.max(1,(seconds+59)/60)+" minutes","Route "+f.route().firstRoute()+" · "+basis+". Open your plan to check."));
            if(seconds<=60 && seconds>=-120)list.add(new Notice("leave-now","It is time to leave","Route "+f.route().firstRoute()+" · "+basis+". Check current conditions before leaving."));
        }
        if(active!=null && !active.secondBoardDone()) {
            if(!active.hasConnection() || !current.second().board().usable() || current.transferMargin()<f.transferBuffer()+f.trafficAllowance())
                list.add(new Notice("transfer-risk","Check your connection","Your estimated transfer is tight or unavailable. Open your trip for the next step."));
            if(active.state().phase()==ActiveJourney.Phase.WAITING_FOR_93) {
                long seconds=Duration.between(now,current.second().board().at()).getSeconds();
                if(seconds>=0 && seconds<=300)list.add(new Notice("second-soon","Your next bus is due soon","Route "+f.route().secondRoute()+" · "+current.second().board().display()+" · "+current.second().board().source()+"."));
            }
        }
        if(!live.fresh(now))list.add(new Notice("feed-unconfirmed","Live bus status is unconfirmed","Current live predictions are unavailable. Check the scheduled times and VIA before continuing."));
        if(current.second().alight().directPrediction() && Math.abs(Duration.between(w.option().atWork(),current.atWork()).toMinutes())>=5)
            list.add(new Notice("arrival-changed","Your arrival estimate changed","Estimated destination arrival: "+current.workDisplay()+". Open your trip to review."));
        if(!current.first().board().usable() && (active==null || !active.firstBoardDone()))list.add(new Notice("first-unavailable","Check your first bus","The selected bus or boarding stop is unavailable in the latest feed. Find another departure."));
        return list;
    }
    @Scheduled(fixedDelayString="${jit.push.check-ms:30000}",initialDelay=30000)
    public void tick() {
        if(!push.available())return;Instant now=clock.instant();watches.deleteByExpiresAtBefore(now);
        var batch=watches.findTop100ByOwnerGreaterThanOrderByOwnerAsc(cursor);
        if(batch.isEmpty() && !cursor.isEmpty()){cursor="";batch=watches.findTop100ByOwnerGreaterThanOrderByOwnerAsc(cursor);}
        if(batch.isEmpty())return;
        cursor=batch.get(batch.size()-1).owner;
        var schedule=schedules.get();var live=realtime.get();
        for(var record:batch) {
            if(record.nextAttempt!=null && now.isBefore(record.nextAttempt))continue;
            var sub=subscribers.findById(record.owner).orElse(null);if(sub==null){watches.deleteRevision(record.owner,record.revision);continue;}
            try {
                var w=json.readValue(record.payload,Watch.class);var active=w.journey()==null?null:ActiveJourney.view(w.journey(),schedule,live,now);
                var current=active==null?ActiveJourney.preview(w.form(),w.option(),schedule,live,now):active.option();
                if(active==null && now.isAfter(current.first().board().at().plusSeconds(120))) { watches.deleteRevision(record.owner,record.revision);continue; }
                Set<String> sent=new HashSet<>(record.sent==null?List.of():Arrays.asList(record.sent.split(",")));
                for(var note:notices(w,current,active,live,now)) {
                    if(sent.contains(note.key()))continue;
                    if(!watches.existsByOwnerAndRevision(record.owner,record.revision))break;
                    int status=push.send(sub,note.title(),note.body(),w.generation()+":"+note.key());
                    if(status==404 || status==410) { subscribers.deleteById(sub.owner);watches.deleteRevision(record.owner,record.revision);break; }
                    if(status>=200 && status<300) { sent.add(note.key());record.sent=String.join(",",sent);record.nextAttempt=now.plusSeconds(30);watches.recordAttempt(record.owner,record.revision,record.sent,record.nextAttempt); }
                    else { record.nextAttempt=now.plusSeconds(120);watches.recordAttempt(record.owner,record.revision,record.sent,record.nextAttempt);break; }
                }
            } catch(Exception e) { if(e instanceof InterruptedException)Thread.currentThread().interrupt();record.nextAttempt=now.plusSeconds(120);watches.recordAttempt(record.owner,record.revision,record.sent,record.nextAttempt);org.slf4j.LoggerFactory.getLogger(getClass()).warn("Notification attempt failed ({})",e.getClass().getSimpleName()); }
        }
    }
}
