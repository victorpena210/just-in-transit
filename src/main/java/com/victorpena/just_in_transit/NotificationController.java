package com.victorpena.just_in_transit;
import java.time.*;import java.util.*;
import org.springframework.web.bind.annotation.*;import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
@RestController @RequestMapping("/notifications") public class NotificationController {
    private final BrowserIdentity browser;private final PushTransport transport;private final PushSubscriptionRepository subscriptions;private final TripWatchRepository watches;private final CommuteNotifications notifications;private final JourneySession journeys;private final ScheduleStore schedules;private final RealtimeStore realtime;private final Clock clock;
    public NotificationController(BrowserIdentity browser,PushTransport transport,PushSubscriptionRepository subscriptions,TripWatchRepository watches,CommuteNotifications notifications,JourneySession journeys,ScheduleStore schedules,RealtimeStore realtime,Clock clock) {this.browser=browser;this.transport=transport;this.subscriptions=subscriptions;this.watches=watches;this.notifications=notifications;this.journeys=journeys;this.schedules=schedules;this.realtime=realtime;this.clock=clock;}
    @GetMapping public Map<String,Object> status() { return Map.of("available",transport.available(),"publicKey",transport.publicKey(),"subscribed",subscriptions.existsById(browser.id()),"watching",watches.existsById(browser.id())); }
    public record Subscription(String endpoint,Map<String,String> keys) { }
    @PostMapping("/subscribe") public Map<String,Boolean> subscribe(@RequestBody Subscription data,HttpServletRequest request) {
        if(!transport.available())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Notifications have not been configured.");
        if(!request.isSecure() && !Set.of("localhost","127.0.0.1","::1").contains(request.getServerName()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"HTTPS is required.");
        try {
            var sub=new PushSubscription();sub.owner=browser.id();sub.endpoint=data.endpoint();sub.p256dh=data.keys().get("p256dh");sub.auth=data.keys().get("auth");PushTransport.validate(sub);subscriptions.save(sub);
            notifications.update(browser.id(),journeys.current(clock.instant()));return Map.of("saved",true);
        } catch(IllegalArgumentException | NullPointerException e) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid push subscription.");}
    }
    @PostMapping("/unsubscribe") public Map<String,Boolean> unsubscribe() { subscriptions.deleteById(browser.id());notifications.stop(browser.id());return Map.of("removed",true); }
    @PostMapping("/stop") public Map<String,Boolean> stop() { notifications.stop(browser.id());return Map.of("stopped",true); }
    @PostMapping("/watch") public Map<String,Boolean> watch(@RequestParam(defaultValue="") String token,@RequestParam(defaultValue="0") int option) {
        if(!subscriptions.existsById(browser.id()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enable notifications first.");
        Instant now=clock.instant();var active=journeys.current(now);
        if(active!=null) {notifications.update(browser.id(),active);return Map.of("watching",true);}
        var choice=journeys.mapChoice(token,option,now);
        if(choice==null || !choice.form().date().equals(now.atZone(TransitModels.ZONE).toLocalDate()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a current departure for today.");
        var selected=ActiveJourney.preview(choice.form(),choice.option(),schedules.get(),realtime.get(),now);
        if(!selected.first().board().usable() || !selected.first().board().at().isAfter(now))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Refresh and choose a usable departure.");
        notifications.watch(browser.id(),new CommuteNotifications.Watch(token+":"+option,choice.form(),selected,null,now),now.plus(Duration.ofHours(18)));return Map.of("watching",true);
    }
}
