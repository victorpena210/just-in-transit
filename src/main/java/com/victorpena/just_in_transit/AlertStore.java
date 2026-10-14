package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import com.google.transit.realtime.GtfsRealtime.*;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AlertStore {
    private final String url;
    private final boolean enabled;
    private Instant attempted=Instant.EPOCH;
    private Snapshot snapshot=Snapshot.empty();
    private boolean downloadFailed;
    public AlertStore(@Value("${via.alerts-url:http://gtfs.viainfo.net/alert/alerts.pb}") String url,
                      @Value("${via.enabled:true}") boolean enabled) { this.url=url;this.enabled=enabled; }
    public synchronized Snapshot get() {
        Instant now=Instant.now();
        if(!enabled || attempted.plusSeconds(60).isAfter(now))return snapshot;
        attempted=now;
        try { snapshot=Snapshot.parse(FeedHttp.fetch(url,5_000_000));downloadFailed=false; }
        catch(Exception e) { if(e instanceof InterruptedException)Thread.currentThread().interrupt();downloadFailed=true; }
        return snapshot;
    }
    public String status(Instant now) {
        if(snapshot.timestamp().equals(Instant.EPOCH))return "Service alerts unavailable. Disruptions have not been checked.";
        if(downloadFailed || !snapshot.fresh(now))return "Current alert status unconfirmed. Any advisories below are from the last downloaded feed.";
        return "VIA service-alert feed checked. Review advisories before travelling.";
    }
    public boolean confirmed(Instant now) { return !downloadFailed && snapshot.fresh(now); }
    public record Advisory(String id,String title,String description,String effect,String url) { }
    public record Context(String route,String tripId,String direction,String serviceDate,String startTime,
                          String agency,int routeType,Set<String> stops,Instant from,Instant to) { }
    public record Snapshot(Instant timestamp,List<FeedEntity> entities) {
        public static Snapshot empty() { return new Snapshot(Instant.EPOCH,List.of()); }
        public static Snapshot parse(byte[] bytes) throws IOException {
            FeedMessage feed=FeedMessage.parseFrom(bytes);
            if(feed.getHeader().getIncrementality()==FeedHeader.Incrementality.DIFFERENTIAL)
                throw new IOException("Differential alert feeds are not supported");
            return new Snapshot(Instant.ofEpochSecond(feed.getHeader().getTimestamp()),feed.getEntityList().stream()
                .filter(e->!e.getIsDeleted() && e.hasAlert()).toList());
        }
        public boolean fresh(Instant now) { long age=Duration.between(timestamp,now).getSeconds();return age>=-30&&age<=600; }
        public String updatedDisplay() { return timestamp.equals(Instant.EPOCH)?"No alert snapshot":
            DateTimeFormatter.ofPattern("MMM d, h:mm:ss a z").withZone(ZONE).format(timestamp); }
        public List<Advisory> relevant(List<Context> contexts) {
            List<Advisory> result=new ArrayList<>();
            for(var entity:entities) {
                Alert alert=entity.getAlert();
                boolean matches=contexts.stream().anyMatch(c->active(alert,c.from(),c.to()) &&
                    alert.getInformedEntityList().stream().anyMatch(s->matches(s,c)));
                if(!matches)continue;
                String title=translation(alert.getHeaderText()),description=translation(alert.getDescriptionText());
                if(title.isBlank())title="VIA service advisory";
                result.add(new Advisory(entity.getId(),title,description,alert.getEffect().name().replace('_',' '),safeUrl(translation(alert.getUrl()))));
            }
            return result.stream().distinct().toList();
        }
    }
    static boolean active(Alert alert,Instant from,Instant to) {
        if(alert.getActivePeriodCount()==0)return true;
        return alert.getActivePeriodList().stream().anyMatch(p->
            (!p.hasStart() || p.getStart()<=to.getEpochSecond()) && (!p.hasEnd() || p.getEnd()>from.getEpochSecond()));
    }
    static boolean matches(EntitySelector s,Context c) {
        // Fields inside one selector are AND; different selectors are OR.
        if(s.hasAgencyId() && !s.getAgencyId().equals(c.agency()))return false;
        if(s.hasRouteId() && !s.getRouteId().equals(c.route()))return false;
        if(s.hasRouteType() && s.getRouteType()!=c.routeType())return false;
        if(s.hasDirectionId() && !Integer.toString(s.getDirectionId()).equals(c.direction()))return false;
        if(s.hasStopId() && !c.stops().contains(s.getStopId()))return false;
        if(s.hasTrip()) {
            var t=s.getTrip();
            if(t.hasTripId() && !t.getTripId().equals(c.tripId()))return false;
            if(t.hasRouteId() && !t.getRouteId().equals(c.route()))return false;
            if(t.hasDirectionId() && !Integer.toString(t.getDirectionId()).equals(c.direction()))return false;
            if(t.hasStartDate() && !t.getStartDate().equals(c.serviceDate()))return false;
            if(t.hasStartTime() && !t.getStartTime().equals(c.startTime()))return false;
        }
        return s.hasAgencyId()||s.hasRouteId()||s.hasRouteType()||s.hasDirectionId()||s.hasStopId()||s.hasTrip();
    }
    static String translation(TranslatedString value) {
        return value.getTranslationList().stream().filter(t->t.getLanguage().equalsIgnoreCase("en") || t.getLanguage().startsWith("en-"))
            .map(TranslatedString.Translation::getText).findFirst().orElseGet(()->value.getTranslationList().stream()
                .filter(t->!t.hasLanguage()).map(TranslatedString.Translation::getText).findFirst()
                .orElse(value.getTranslationCount()>0?value.getTranslation(0).getText():""));
    }
    static String safeUrl(String text) {
        try { URI uri=URI.create(text);return ("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))
            && uri.getHost()!=null && uri.getUserInfo()==null?text:null; }catch(Exception e) { return null; }
    }
    public static List<Context> contexts(CommuteForm form,Schedule schedule,Result result,Instant now) {
        if(schedule==null)return List.of();
        List<Context> out=new ArrayList<>();
        if(result!=null && !result.options().isEmpty()) {
            for(Option option:result.options()) {
                out.add(context(schedule,option.first(),form.originStop(),form.route().transferOff()));
                out.add(context(schedule,option.second(),form.route().transferOn(),form.destinationStop()));
                if(option.backup()!=null)out.add(context(schedule,option.backup().leg(),form.route().transferOn(),form.destinationStop()));
            }
        }else {
            LocalDate day=form.date()==null?now.atZone(ZONE).toLocalDate():form.date();
            Instant from=day.atStartOfDay(ZONE).toInstant(),to=day.plusDays(1).atStartOfDay(ZONE).toInstant().minusNanos(1);
            for(String route:List.of("24","93")) {
                var info=schedule.routes().getOrDefault(route,new RouteInfo("",3));
                Set<String> stops=new HashSet<>();
                String selected=route.equals(form.route().firstRoute())?form.originStop():form.destinationStop();if(selected!=null)stops.add(selected);
                stops.add(route.equals(form.route().firstRoute())?form.route().transferOff():form.route().transferOn());
                out.add(new Context(route,"","",day.format(DateTimeFormatter.BASIC_ISO_DATE),"",info.agency(),info.type(),stops,from,to));
            }
        }
        return out;
    }
    private static Context context(Schedule schedule,Leg leg,String start,String end) {
        Trip trip=schedule.trips().stream().filter(t->t.id().equals(leg.tripId())).findFirst().orElse(null);
        if(trip==null) {
            var info=schedule.routes().getOrDefault(leg.route(),new RouteInfo("",3));
            return new Context(leg.route(),leg.tripId(),"",leg.serviceDate().format(DateTimeFormatter.BASIC_ISO_DATE),"",info.agency(),info.type(),Set.of(start,end),leg.board().at(),leg.alight().at());
        }
        Set<String> stops=new HashSet<>();boolean collecting=false;
        for(StopTime stop:trip.stops()) {
            if(stop.id().equals(start))collecting=true;
            if(collecting)stops.add(stop.id());
            if(collecting && stop.id().equals(end))break;
        }
        RouteInfo info=schedule.routes().getOrDefault(leg.route(),new RouteInfo("",3));
        int seconds=trip.stops().isEmpty()?0:trip.stops().get(0).departure();
        String startTime=String.format(Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60);
        return new Context(leg.route(),leg.tripId(),trip.direction(),leg.serviceDate().format(DateTimeFormatter.BASIC_ISO_DATE),
            startTime,info.agency(),info.type(),stops,leg.board().at(),leg.alight().at());
    }
}
