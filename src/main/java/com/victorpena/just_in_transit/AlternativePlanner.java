package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;import java.util.*;
import org.springframework.stereotype.Service;

/** Bounded network search: direct rides or one transfer, at the same stop or a GTFS-listed transfer pair. */
@Service
public class AlternativePlanner {
    public record Ride(Trip trip,Stop from,Stop to,Event board,Event alight,LocalDate day) {
        public String label() { return trip.route()+" · "+trip.headsign(); }
    }
    public record Choice(List<Ride> rides,Instant leave,Instant arrival,int transferMinutes,long waitMinutes,boolean fits,boolean timedTransfer) {
        public String leaveDisplay() { return time(leave); } public String arrivalDisplay() { return time(arrival); }
        public String routes() { return String.join(" → ",rides.stream().map(x->x.trip().route()).toList()); }
        public String status() { return fits?"Wait preference met":"Longer wait than requested"; }
    }
    public List<Choice> search(Schedule s,LiveSnapshot live,String origin,String destination,Instant leaveAfter,
        int firstWalk,int lastWalk,int transferWalk,int boardingBuffer,int transferBuffer,int maxWait) {
        return search(s,live,origin,destination,leaveAfter,firstWalk,lastWalk,transferWalk,boardingBuffer,transferBuffer,maxWait,leaveAfter);
    }
    public List<Choice> search(Schedule s,LiveSnapshot live,String origin,String destination,Instant leaveAfter,
        int firstWalk,int lastWalk,int transferWalk,int boardingBuffer,int transferBuffer,int maxWait,Instant checkedAt) {
        return search(s,live,origin,destination,leaveAfter,firstWalk,lastWalk,transferWalk,boardingBuffer,transferBuffer,maxWait,checkedAt,leaveAfter.plusSeconds(6*3600),null);
    }
    public List<Choice> forCommute(Schedule s,LiveSnapshot live,CommuteForm f,Instant checkedAt) {
        if(f.atStop() || f.onboard())return List.of();
        Instant deadline=f.date().atTime(f.arriveBy()).atZone(ZONE).toInstant();
        Instant from=f.departing()?deadline:deadline.minusSeconds(6*3600);
        if(from.isBefore(checkedAt))from=checkedAt;
        Instant end=f.departing()?from.plusSeconds(6*3600):deadline;
        if(!from.isBefore(end))return List.of();
        return search(s,live,f.originStop(),f.destinationStop(),from,f.walkToStop(),f.walkToWork(),f.transferWalk(),f.boardingBuffer(),
            f.transferBuffer()+f.trafficAllowance(),f.maxTransferWait(),checkedAt,end,f);
    }
    private List<Choice> search(Schedule s,LiveSnapshot live,String origin,String destination,Instant leaveAfter,
        int firstWalk,int lastWalk,int transferWalk,int boardingBuffer,int transferBuffer,int maxWait,Instant checkedAt,Instant end,CommuteForm window) {
        if(s==null || origin.equals(destination) || !s.stops().containsKey(origin) || !s.stops().containsKey(destination))return List.of();
        Instant earliest=leaveAfter.plusSeconds((firstWalk+boardingBuffer)*60L);
        var first=new ArrayList<Ride>();var incoming=new HashMap<String,List<Ride>>();var choices=new ArrayList<Choice>();
        var byFrom=new HashMap<String,List<Transfer>>();s.transfers().forEach(t->byFrom.computeIfAbsent(t.from(),k->new ArrayList<>()).add(t));
        LocalDate day=leaveAfter.atZone(ZONE).toLocalDate();
        for(var date:List.of(day.minusDays(1),day,day.plusDays(1)))for(var trip:s.trips()) {
            if(!s.active(trip.service(),date))continue;
            List<StopTime> times=trip.stops();
            for(int i=0;i<times.size();i++) {
                var start=times.get(i);
                if(start.id().equals(origin) && start.pickup()) {
                    Event board=live.event(trip,date,start,true,checkedAt);
                    if(!board.usable() || board.at().isBefore(earliest) || board.at().isAfter(end))continue;
                    for(int j=i+1;j<times.size();j++) {
                        var stop=times.get(j);if(!stop.dropoff() || !s.stops().containsKey(stop.id()))continue;
                        Event alight=live.event(trip,date,stop,false,checkedAt);
                        if(!alight.usable() || alight.at().isBefore(board.at()) || alight.at().isAfter(end))continue;
                        var ride=new Ride(trip,s.stops().get(origin),s.stops().get(stop.id()),board,alight,date);first.add(ride);
                        if(stop.id().equals(destination))choices.add(new Choice(List.of(ride),board.at().minusSeconds((firstWalk+boardingBuffer)*60L),alight.at().plusSeconds(lastWalk*60L),0,0,true,false));
                    }
                }
                if(start.id().equals(destination) && start.dropoff()) {
                    Event alight=live.event(trip,date,start,false,checkedAt);
                    if(!alight.usable() || alight.at().isBefore(earliest) || alight.at().isAfter(end))continue;
                    for(int j=0;j<i;j++) {
                        var stop=times.get(j);if(!stop.pickup() || !s.stops().containsKey(stop.id()))continue;
                        Event board=live.event(trip,date,stop,true,checkedAt);
                        if(board.usable() && !board.at().isBefore(earliest) && !alight.at().isBefore(board.at()))
                            incoming.computeIfAbsent(stop.id(),k->new ArrayList<>()).add(new Ride(trip,s.stops().get(stop.id()),s.stops().get(destination),board,alight,date));
                    }
                }
            }
        }
        for(var a:first) {
            if(a.to().id().equals(destination))continue;
            Set<String> targets=new HashSet<>();targets.add(a.to().id());
            byFrom.getOrDefault(a.to().id(),List.of()).forEach(t->targets.add(t.to()));
            for(String target:targets)for(var b:incoming.getOrDefault(target,List.of())) {
                if(a.trip().id().equals(b.trip().id()) || a.trip().route().equals(b.trip().route()))continue;
                var rules=byFrom.getOrDefault(a.to().id(),List.of()).stream().filter(t->t.to().equals(target) && t.matches(a.trip(),b.trip())).toList();
                if(rules.stream().anyMatch(t->t.type()<0 || t.type()>2) || !target.equals(a.to().id()) && rules.isEmpty())continue;
                int seconds=Math.max(transferWalk*60,rules.stream().mapToInt(Transfer::minimumSeconds).max().orElse(0));
                long wait=Duration.between(a.alight().at().plusSeconds(seconds),b.board().at()).getSeconds();
                if(wait<transferBuffer*60L || wait>(window==null?90:120)*60L)continue;
                choices.add(new Choice(List.of(a,b),a.board().at().minusSeconds((firstWalk+boardingBuffer)*60L),b.alight().at().plusSeconds(lastWalk*60L),
                    (seconds+59)/60,wait/60,wait<=maxWait*60L,rules.stream().anyMatch(t->t.type()==1)));
            }
        }
        // Diversity by route sequence and transfer pair, not dozens of copies of the same connection.
        var result=new LinkedHashMap<String,Choice>();
        Comparator<Choice> ranking=Comparator.comparing(Choice::fits).reversed();
        if(window!=null && !window.departing())ranking=ranking.thenComparingLong(c->arrivalExcess(window,c)).thenComparing(Choice::leave,Comparator.reverseOrder()).thenComparing(Choice::arrival);
        else ranking=ranking.thenComparing(Choice::arrival).thenComparing(Choice::leave,Comparator.reverseOrder());
        choices.stream().filter(c->!c.arrival().isAfter(end))
            .map(c->window==null?c:new Choice(c.rides(),c.leave(),c.arrival(),c.transferMinutes(),c.waitMinutes(),c.fits() && arrivalExcess(window,c)==0 && window.boardingBuffer()<=window.maxBoardingWait(),c.timedTransfer()))
            .filter(c->window==null || !c.routes().equals(window.route().routes()))
            .sorted(ranking).forEach(c->{
            String key=c.routes()+":"+c.rides().get(0).to().id()+":"+c.rides().get(c.rides().size()-1).from().id();result.putIfAbsent(key,c);
        });
        return result.values().stream().limit(5).toList();
    }
    static long arrivalExcess(CommuteForm f,Choice c) {
        if(f.departing())return 0;
        long margin=Duration.between(c.arrival(),f.date().atTime(f.arriveBy()).atZone(ZONE).toInstant()).getSeconds();
        return Math.max(0,margin-f.maxEarlyArrival()*60L)+Math.max(0,(f.arrivalBuffer()+f.trafficAllowance())*60L-margin);
    }
    public static String preferenceNote(CommuteForm f,Choice c) {
        var notes=new ArrayList<String>();
        if(!f.departing()) {
            long margin=Duration.between(c.arrival(),f.date().atTime(f.arriveBy()).atZone(ZONE).toInstant()).toMinutes();
            notes.add(margin>=0?margin+" min early":(-margin)+" min late");
            if(margin>f.maxEarlyArrival())notes.add((margin-f.maxEarlyArrival())+" min earlier than your limit");
            if(margin<f.arrivalBuffer()+f.trafficAllowance())notes.add("Less arrival cushion than requested");
        }
        if(c.waitMinutes()>f.maxTransferWait())notes.add((c.waitMinutes()-f.maxTransferWait())+" min over your transfer wait limit");
        return (c.fits()?"Fits your preferences":"Outside your preferences")+(notes.isEmpty()?"":" · "+String.join(" · ",notes));
    }
}
