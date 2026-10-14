package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Normalized live data and GTFS timing rules, independent of protobuf/network I/O. */
public class LiveSnapshot {
    public record Prediction(Instant time,Long delay,Integer uncertainty) { }
    public record StopPrediction(Integer sequence,String stopId,String relationship,Prediction arrival,Prediction departure) { }
    public record TripPrediction(String startDate,String relationship,Instant timestamp,Long delay,List<StopPrediction> stops) { }
    protected final Instant timestamp;
    protected final Map<String,List<TripPrediction>> trips;
    public LiveSnapshot(Instant timestamp,Map<String,List<TripPrediction>> trips) {
        this.timestamp=timestamp;
        Map<String,List<TripPrediction>> copy=new HashMap<>();
        trips.forEach((id,values)->copy.put(id,List.copyOf(values)));
        this.trips=Map.copyOf(copy);
    }
    public Instant timestamp() { return timestamp; }
    public Map<String,List<TripPrediction>> trips() { return trips; }
    public static LiveSnapshot empty() { return new LiveSnapshot(Instant.EPOCH,Map.of()); }
        public boolean fresh(Instant now) { return recent(timestamp,now); }
        private static boolean recent(Instant stamp,Instant now) {
            long age=Duration.between(stamp,now).getSeconds();return age>=-30 && age<=120;
        }
        public String updatedDisplay() {
            return timestamp.equals(Instant.EPOCH)?"No live snapshot":DateTimeFormatter.ofPattern("MMM d, h:mm:ss a z").withZone(ZONE).format(timestamp);
        }
        public Event event(Trip trip,LocalDate day,StopTime stop,boolean boarding,Instant now) {
            Instant scheduled=serviceTime(day,boarding?stop.departure():stop.arrival());
            Event fallback=new Event(scheduled,"Scheduled",false,true);
            if(!fresh(now))return fallback;
            String serviceDate=day.format(DateTimeFormatter.BASIC_ISO_DATE);
            TripPrediction update=trips.getOrDefault(trip.id(),List.of()).stream().filter(u->
                u.startDate()!=null?u.startDate().equals(serviceDate):day.equals(timestamp.atZone(ZONE).toLocalDate())
            ).findFirst().orElse(null);
            if(update==null)return fallback;
            // Never use an old trip estimate merely because the enclosing feed is new.
            if(update.timestamp()!=null && !recent(update.timestamp(),now))return fallback;
            String relationship=update.relationship();
            if(!relationship.equals("SCHEDULED"))return new Event(scheduled,"Trip unavailable",false,false);
            // Resolve stop IDs against the static trip so omitted stops inherit the last
            // known delay. A repeated stop ID without a sequence is ambiguous.
            Map<Integer,StopPrediction> updates=new HashMap<>();
            for(var item:update.stops()) {
                var matches=trip.stops().stream().filter(t->item.sequence()!=null
                    ? t.sequence()==item.sequence() : t.id().equals(item.stopId())).toList();
                if(matches.size()==1)updates.put(matches.get(0).sequence(),item);
            }
            Long carried=update.delay()!=null && Math.abs(update.delay())<=21600 ? update.delay() : null;
            for(var current:trip.stops().stream().sorted(Comparator.comparingInt(StopTime::sequence)).toList()) {
                if(current.sequence()>stop.sequence())break;
                var item=updates.get(current.sequence());
                boolean target=current.sequence()==stop.sequence();
                if(item==null) {
                    if(target)return carried==null?fallback:new Event(scheduled.plusSeconds(carried),"Live delay · carried forward",true,true);
                    continue;
                }
                var relation=item.relationship();
                if(relation.equals("SKIPPED")) {
                    if(target)return new Event(scheduled,"Stop skipped",false,false);
                    continue; // SKIPPED does not terminate propagation.
                }
                if(relation.equals("NO_DATA")) {
                    carried=null;
                    if(target)return fallback;
                    continue;
                }
                // A SCHEDULED update replaces the previous delay, even when it has no usable timing.
                carried=null;
                Prediction arrival=item.arrival();
                Prediction departure=item.departure();
                if(target) {
                    var event=boarding && departure!=null?departure:arrival;
                    boolean proxy=boarding && departure==null && arrival!=null;
                    Instant base=serviceTime(day,proxy || !boarding?current.arrival():current.departure());
                    Long delay=delay(event,base);
                    if(delay==null)return fallback;
                    String source=proxy?"Live arrival · boarding proxy":"Live estimate";
                    if(event.uncertainty()!=null && event.uncertainty()>0)source+=" · uncertainty "+event.uncertainty()+"s";
                    return new Event(base.plusSeconds(delay),source,true,true);
                }
                // Arrival is the propagation source when departure is omitted. If a
                // departure is supplied but unusable, do not invent its delay.
                carried=departure!=null?delay(departure,serviceTime(day,current.departure()))
                    :delay(arrival,serviceTime(day,current.arrival()));
            }
            return fallback;
        }
        private static Long delay(Prediction event,Instant scheduled) {
            if(event==null)return null;
            Long seconds=event.time()!=null?Long.valueOf(Duration.between(scheduled,event.time()).getSeconds())
                :event.delay();
            return seconds!=null && Math.abs(seconds)<=21600?seconds:null;
        }
}
