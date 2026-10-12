package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CommutePlanner {
    public Result plan(CommuteForm f,Schedule schedule,RealtimeStore.Snapshot live,Instant now) {
        if(schedule==null)return new Result(List.of(),"VIA's schedule is unavailable. Check the connection and try again.");
        Instant deadline=f.date().atTime(f.arriveBy()).atZone(ZONE).toInstant();
        if(!deadline.isAfter(now))return new Result(List.of(),"Choose an arrival date and time in the future.");
        List<Leg> first=legs(schedule,live,"24",f.originStop(),"76779",f.date(),now);
        List<Leg> second=legs(schedule,live,"93","76773",f.destinationStop(),f.date(),now);
        if(first.isEmpty() || second.isEmpty())return new Result(List.of(),"No matching service was found in the downloaded schedule for this date and entrance. Try another date or check VIA's timetable.");
        List<Option> options=new ArrayList<>();
        for(var a:first) {
            Instant leave=a.board().at().minusSeconds((f.walkToStop()+f.boardingBuffer())*60L);
            if(leave.isBefore(now) || a.board().at().isBefore(deadline.minusSeconds(8*3600)))continue;
            List<Option> connections=new ArrayList<>();
            for(var b:second) {
                long marginSeconds=Duration.between(a.alight().at().plusSeconds(f.transferWalk()*60L),b.board().at()).getSeconds();
                if(marginSeconds<0 || marginSeconds>90*60)continue;
                Instant atWork=b.alight().at().plusSeconds(f.walkToWork()*60L);
                long arrivalSeconds=Duration.between(atWork,deadline).getSeconds();
                if(arrivalSeconds<0 || arrivalSeconds>6*3600)continue;
                boolean transferOk=marginSeconds>=f.transferBuffer()*60L,arrivalOk=arrivalSeconds>=f.arrivalBuffer()*60L;
                String rating=transferOk&&arrivalOk?"Buffers met":!transferOk&&!arrivalOk?"Tight transfer + arrival":!transferOk?"Tight transfer":"Tight arrival";
                Leg next=second.stream().filter(n->n.board().at().isAfter(b.board().at())
                        && !n.tripId().equals(b.tripId())
                        && !n.board().at().isBefore(a.alight().at().plusSeconds(f.transferWalk()*60L)))
                    .min(Comparator.comparing(n->n.board().at())).orElse(null);
                Backup backup=next==null?null:new Backup(next,next.alight().at().plusSeconds(f.walkToWork()*60L),
                    Duration.between(b.board().at(),next.board().at()).toMinutes(),
                    Duration.between(next.alight().at().plusSeconds(f.walkToWork()*60L),deadline).getSeconds());
                connections.add(new Option(a,b,leave,atWork,marginSeconds/60,arrivalSeconds/60,marginSeconds/60,
                    transferOk&&arrivalOk,rating,backup==null?null:backup.minutesLater(),backup));
            }
            // One option per first bus: prefer requested buffers, then the least transfer waiting.
            connections.stream().sorted(Comparator.comparing(Option::meetsBuffers).reversed()
                .thenComparing(o->o.second().board().at())).findFirst().ifPresent(options::add);
        }
        List<Option> result=options.stream().sorted(Comparator.comparing(Option::meetsBuffers).reversed()
            .thenComparing(Option::leaveHome,Comparator.reverseOrder())).limit(3).toList();
        return new Result(result,result.isEmpty()?"No remaining 24 → 93 connection meets your arrival time within this planner's search window. Try an earlier travel date/time or check another route.":
            result.get(0).meetsBuffers()?"Latest departure that meets your walking times and requested buffers, followed by other options.":
            "These connections fit the times, but none meets every buffer you requested. Consider an earlier trip.");
    }
    static List<Leg> legs(Schedule schedule,RealtimeStore.Snapshot live,String route,String from,String to,LocalDate date,Instant now) {
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
    public List<BoardRow> board(Schedule schedule,RealtimeStore.Snapshot live,Instant now) {
        return board(schedule,live,now,"81799","83463");
    }
    public List<BoardRow> board(Schedule schedule,RealtimeStore.Snapshot live,Instant now,String originStop,String destinationStop) {
        if(schedule==null)return List.of();
        List<BoardRow> rows=new ArrayList<>();
        LocalDate today=now.atZone(ZONE).toLocalDate();
        for(String route:List.of("24","93")) {
            String from=route.equals("24")?originStop:"76773",to=route.equals("24")?"76779":destinationStop;
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
