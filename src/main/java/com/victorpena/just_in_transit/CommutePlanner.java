package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CommutePlanner {
    public Result plan(CommuteForm f,Schedule schedule,LiveSnapshot live,Instant now) {
        if(schedule==null)return new Result(List.of(),"VIA's schedule is unavailable. Check the connection and try again.");
        if(f.date().isBefore(now.atZone(ZONE).toLocalDate()))return new Result(List.of(),"Choose today or a future travel date.");
        Instant deadline=f.date().atTime(f.arriveBy()).atZone(ZONE).toInstant();
        Instant horizon=(f.onboard() || f.atStop()?now:deadline.isAfter(now)?deadline:now).plusSeconds(6*3600);
        List<Option> candidates=connections(f,schedule,live,now,horizon,false,Set.of());
        List<Option> eligible=f.departing()?candidates:candidates.stream().filter(o->o.arrivalMargin()>=0).toList();
        if(eligible.isEmpty()) {
            var late=candidates.stream().sorted(earliestArrival()).limit(3).toList();
            return new Result(late,late.isEmpty()?"No remaining usable "+f.route().routes()+" connection was found on your saved route within the search window. Check VIA for another route.":
                "No on-time connection remains on your saved route. Earliest remaining arrivals are shown, including your final walk. Your deadline is unchanged.");
        }
        var ranked=eligible.stream().sorted(ranking(f)).toList();
        Option best=ranked.get(0);
        // Show genuinely different first buses when possible, then other connections on the same bus.
        List<Option> result=new ArrayList<>();result.add(best);
        for(var o:ranked)if(result.size()<3 && result.stream().noneMatch(r->legKey(r.first()).equals(legKey(o.first()))))result.add(o);
        for(var o:ranked)if(result.size()<3 && !result.contains(o))result.add(o);
        String choice=f.departing()?"Departures after your selected time; no arrival deadline is set.":f.arrivalWindowLabel();
        String preference=switch(f.preference()) { case "latest" -> "Latest departure that fits your limits on this route.";case "safer" -> "More room for delays within your limits.";default -> "Shorter complete commute within your limits."; };
        return new Result(List.copyOf(result),choice+" "+(best.meetsBuffers()?
            (f.departing()?"Earliest arrival that fits your waits.":preference):"No trip fits all your preferences on your saved route. Closest options are shown with their trade-offs; your limits have not changed."));
    }
    private static Comparator<Option> ranking(CommuteForm f) {
        Comparator<Option> base=Comparator.comparing(Option::meetsBuffers).reversed()
            .thenComparingLong(o->CommuteTiming.arrivalExcess(f,o))
            .thenComparingLong(o->CommuteTiming.waitExcess(f,o));
        if(f.departing())return base.thenComparing(earliestArrival());
        return switch(f.preference()) {
            case "latest" -> base.thenComparing(Option::leaveHome,Comparator.reverseOrder()).thenComparing(earliestArrival());
            case "safer" -> base.thenComparing(Comparator.comparingLong((Option o)->Math.min(o.transferMargin()-f.transferBuffer()-f.trafficAllowance(),o.arrivalMargin()-f.arrivalBuffer()-f.trafficAllowance())).reversed())
                .thenComparing(earliestArrival());
            default -> base.thenComparingLong(o->Duration.between(o.leaveHome(),o.atWork()).getSeconds())
                .thenComparing(Option::leaveHome,Comparator.reverseOrder()).thenComparing(earliestArrival());
        };
    }
    /** Replan from the boarding stop: keep the original deadline and final walk, omit the home walk. */
    static Option recover24(CommuteForm f,Schedule schedule,LiveSnapshot live,Instant now,Set<String> missed) {
        if(schedule==null)return null;
        return connections(f,schedule,live,now,now.plusSeconds(6*3600),true,missed).stream()
            .min(earliestArrival()).orElse(null);
    }
    public record BoardingChoice(String key,String label) { }
    public List<BoardingChoice> boardingChoices(CommuteForm f,Schedule schedule,LiveSnapshot live,Instant now) {
        if(schedule==null || !now.atZone(ZONE).toLocalDate().equals(f.date()))return List.of();
        return windowLegs(schedule,live,f.route().firstRoute(),f.originStop(),f.route().transferOff(),f.date(),now).stream()
            .filter(l->joinable(l,now)).map(l->new BoardingChoice(legKey(l),"Route "+l.route()+" · boarding estimate "+l.board().display()+
                " · Martin & Leona "+l.alight().display()+" · trip "+l.tripId())).toList();
    }
    static boolean joinable(Leg leg,Instant now) {
        return !leg.board().at().isBefore(now.minusSeconds(2*3600)) && !leg.board().at().isAfter(now.plusSeconds(300))
            && leg.alight().at().isAfter(now) && leg.board().usable() && leg.alight().usable();
    }
    private static Comparator<Option> earliestArrival() {
        return Comparator.comparing(Option::atWork).thenComparing(Comparator.comparing(Option::transferMargin).reversed())
            .thenComparing(Option::leaveHome,Comparator.reverseOrder());
    }
    static String legKey(Leg leg) { return leg.tripId()+"@"+leg.serviceDate(); }
    private static List<Leg> windowLegs(Schedule s,LiveSnapshot live,String route,String from,String to,LocalDate day,Instant now) {
        var all=new ArrayList<>(legs(s,live,route,from,to,day,now));
        all.addAll(legs(s,live,route,from,to,day.plusDays(1),now));
        return all.stream().distinct().sorted(Comparator.comparing(l->l.board().at())).toList();
    }
    private static List<Option> connections(CommuteForm f,Schedule schedule,LiveSnapshot live,Instant now,
                                            Instant horizon,boolean atStop,Set<String> missed) {
        Instant deadline=f.date().atTime(f.arriveBy()).atZone(ZONE).toInstant();
        LocalDate day=atStop?now.atZone(ZONE).toLocalDate():f.date();
        List<Leg> first=windowLegs(schedule,live,f.route().firstRoute(),f.originStop(),f.route().transferOff(),day,now);
        List<Leg> second=windowLegs(schedule,live,f.route().secondRoute(),f.route().transferOn(),f.destinationStop(),day,now);
        boolean onboard=!atStop && f.onboard(), stop=atStop || f.atStop();
        Instant earliest=f.departing() && !onboard && !stop && deadline.isAfter(now)?deadline:now;
        List<Option> options=new ArrayList<>();
        for(var a:first) {
            Instant leave=onboard || stop?now:a.board().at().minusSeconds((f.walkToStop()+f.boardingBuffer())*60L);
            if(missed.contains(legKey(a)) || a.board().at().isAfter(horizon))continue;
            if(onboard) { if(!legKey(a).equals(f.firstTrip()) || !joinable(a,now))continue; }
            else if(a.board().at().isBefore(earliest) || !stop && leave.isBefore(earliest))continue;
            if(!f.departing() && !onboard && !stop && a.board().at().isBefore(deadline.minusSeconds(8*3600)))continue;

            for(var b:second) {
                long marginSeconds=Duration.between(a.alight().at().plusSeconds(f.transferWalk()*60L),b.board().at()).getSeconds();
                if(marginSeconds<0 || marginSeconds>120*60)continue;
                Instant atWork=b.alight().at().plusSeconds(f.walkToWork()*60L);
                long arrivalSeconds=Duration.between(atWork,deadline).getSeconds();
                if(atWork.isAfter(horizon) || !f.departing() && !atStop && arrivalSeconds>6*3600)continue;
                long firstWait=onboard?0:stop?Duration.between(now,a.board().at()).getSeconds():f.boardingBuffer()*60L;
                boolean transferOk=marginSeconds>=(f.transferBuffer()+f.trafficAllowance())*60L;
                boolean arrivalOk=f.departing() || arrivalSeconds>=(f.arrivalBuffer()+f.trafficAllowance())*60L;
                boolean meets=CommuteTiming.meets(f,firstWait,marginSeconds,arrivalSeconds);
                String rating=f.departing()?(transferOk?"Buffers met":"Tight transfer"):arrivalSeconds<0?"Late arrival":transferOk&&arrivalOk?"Buffers met":!transferOk&&!arrivalOk?"Tight transfer + arrival":!transferOk?"Tight transfer":"Tight arrival";
                Leg next=second.stream().filter(n->n.board().at().isAfter(b.board().at()) && !legKey(n).equals(legKey(b)))
                    .min(Comparator.comparing(n->n.board().at())).orElse(null);
                Backup backup=next==null?null:new Backup(next,next.alight().at().plusSeconds(f.walkToWork()*60L),
                    Duration.between(b.board().at(),next.board().at()).toMinutes(),
                    Duration.between(next.alight().at().plusSeconds(f.walkToWork()*60L),deadline).getSeconds(),!f.departing());
                if(!onboard && firstWait<f.boardingBuffer()*60L)rating="Tight first boarding";
                else if(firstWait>f.maxBoardingWait()*60L || marginSeconds>f.maxTransferWait()*60L)rating="Longer wait than preferred";
                if(!f.departing() && arrivalSeconds<0)rating="Late arrival";
                else if(!f.departing() && arrivalSeconds>f.maxEarlyArrival()*60L)rating="Earlier than your limit";
                options.add(new Option(a,b,leave,atWork,marginSeconds/60,f.departing()?0:Math.floorDiv(arrivalSeconds,60),marginSeconds/60,
                    meets,rating,backup==null?null:backup.minutesLater(),backup,!f.departing()));
            }

        }
        return options;
    }
    static List<Leg> legs(Schedule schedule,LiveSnapshot live,String route,String from,String to,LocalDate date,Instant now) {
        List<Leg> result=new ArrayList<>();
        for(var day:List.of(date.minusDays(1),date))for(var trip:schedule.trips()) {
            if(!trip.route().equals(route) || !schedule.active(trip.service(),day))continue;
            StopTime start=null;
            for(var stop:trip.stops()) {
                if(start==null && stop.id().equals(from) && stop.pickup())start=stop;
                else if(start!=null && stop.id().equals(to) && stop.dropoff()) {
                    Event board=live.event(trip,day,start,true,now),alight=live.event(trip,day,stop,false,now);
                    if(board.usable() && alight.usable() && !alight.at().isBefore(board.at()))result.add(new Leg(route,trip.id(),board,alight,day));
                    break;
                }
            }
        }
        result.sort(Comparator.comparing(l->l.board().at()));return result;
    }
    public List<BoardRow> board(Schedule schedule,LiveSnapshot live,Instant now) {
        return board(schedule,live,now,"81799","83463");
    }
    public List<BoardRow> board(Schedule schedule,LiveSnapshot live,Instant now,String originStop,String destinationStop) {
        return board(schedule,live,now,originStop,destinationStop,Corridor.of("work"));
    }
    public List<BoardRow> board(Schedule schedule,LiveSnapshot live,Instant now,String originStop,String destinationStop,Corridor corridor) {
        if(schedule==null)return List.of();
        List<BoardRow> rows=new ArrayList<>();
        LocalDate today=now.atZone(ZONE).toLocalDate();
        for(String route:List.of(corridor.firstRoute(),corridor.secondRoute())) {
            String from=route.equals(corridor.firstRoute())?originStop:corridor.transferOn(),to=route.equals(corridor.firstRoute())?corridor.transferOff():destinationStop;
            // Include tomorrow's service when the next bus crosses local midnight.
            List<Leg> candidates=new ArrayList<>(legs(schedule,live,route,from,to,today,now));
            candidates.addAll(legs(schedule,live,route,from,to,today.plusDays(1),now));
            candidates.stream().filter(l->!l.board().at().isBefore(now) && l.board().at().isBefore(now.plusSeconds(7200)))
                .distinct().sorted(Comparator.comparing(l->l.board().at())).limit(2)
                .forEach(l->rows.add(new BoardRow(route,schedule.stops().containsKey(from)?schedule.stops().get(from).name():from,l.board(),l.tripId())));
        }
        return rows;
    }
}
