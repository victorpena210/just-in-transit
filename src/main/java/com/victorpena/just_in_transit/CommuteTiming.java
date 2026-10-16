package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;

/** Extra delay is a planning scenario, never an invented live prediction. */
public final class CommuteTiming {
    private CommuteTiming() { }
    public record Summary(long firstWait,long transferWait,long totalMinutes,long transferReserve,
                          String atFirstStop,String transferReady,String trafficArrival,
                          boolean fitsWindows,String trafficNote,String preferenceNote) { }
    public static Summary summarize(CommuteForm f,Option o) {
        Instant atStop=f.atStop() || f.onboard()?o.leaveHome():o.leaveHome().plusSeconds(f.walkToStop()*60L);
        long first=f.onboard()?0:Math.max(0,Duration.between(atStop,o.first().board().at()).toMinutes());
        long reserve=o.transferMargin()-f.trafficAllowance();
        boolean fits=(f.onboard() || first>=f.boardingBuffer() && first<=f.maxBoardingWait())
            && o.transferWait()>=f.transferBuffer()+f.trafficAllowance() && o.transferWait()<=f.maxTransferWait();
        String traffic=f.trafficAllowance()==0?"No extra traffic allowance set. Times may change.":
            "Allows "+f.trafficAllowance()+" extra min on each bus beyond its current estimate. "
            +(reserve<0?"The transfer would be missed in this scenario.":"Arrival with the second-bus allowance: "+time(o.atWork().plusSeconds(f.trafficAllowance()*60L))+".");
        return new Summary(first,o.transferWait(),Math.max(0,Duration.between(o.leaveHome(),o.atWork()).toMinutes()),reserve,
            time(atStop),time(o.first().alight().at().plusSeconds(f.transferWalk()*60L)),time(o.atWork().plusSeconds(f.trafficAllowance()*60L)),fits && arrivalFits(f,arrivalSeconds(f,o)),traffic,explain(f,o,first));
    }
    public static boolean meets(CommuteForm f,long firstWaitSeconds,long transferSeconds,long arrivalSeconds) {
        return (f.onboard() || firstWaitSeconds>=f.boardingBuffer()*60L && firstWaitSeconds<=f.maxBoardingWait()*60L)
            && transferSeconds>=(f.transferBuffer()+f.trafficAllowance())*60L
            && transferSeconds<=f.maxTransferWait()*60L
            && arrivalFits(f,arrivalSeconds);
    }
    public static long arrivalSeconds(CommuteForm f,Option o) { return Duration.between(o.atWork(),f.date().atTime(f.arriveBy()).atZone(ZONE).toInstant()).getSeconds(); }
    public static boolean arrivalFits(CommuteForm f,long seconds) {
        return f.departing() || seconds>=(f.arrivalBuffer()+f.trafficAllowance())*60L && seconds<=f.maxEarlyArrival()*60L;
    }
    public static long arrivalExcess(CommuteForm f,Option o) {
        if(f.departing())return 0;
        long seconds=arrivalSeconds(f,o);
        return Math.max(0,seconds-f.maxEarlyArrival()*60L)+Math.max(0,(f.arrivalBuffer()+f.trafficAllowance())*60L-seconds);
    }
    private static String explain(CommuteForm f,Option o,long first) {
        var notes=new java.util.ArrayList<String>();long arrival=arrivalSeconds(f,o);
        if(!f.departing()) {
            if(arrival>f.maxEarlyArrival()*60L)notes.add((long)Math.ceil((arrival-f.maxEarlyArrival()*60L)/60.0)+" min earlier than your limit");
            if(arrival<0)notes.add((long)Math.ceil(-arrival/60.0)+" min late");
            else if(arrival<(f.arrivalBuffer()+f.trafficAllowance())*60L)notes.add("Less arrival cushion than requested");
        }
        if(!f.onboard() && first<f.boardingBuffer())notes.add("First-stop wait below your minimum");
        if(!f.onboard() && first>f.maxBoardingWait())notes.add((first-f.maxBoardingWait())+" min over your first-stop wait limit");
        if(o.transferWait()>f.maxTransferWait())notes.add((o.transferWait()-f.maxTransferWait())+" min over your transfer wait limit");
        if(o.transferMargin()<f.transferBuffer()+f.trafficAllowance())notes.add("Transfer leaves less room for delays than requested");
        return notes.isEmpty()?"Fits your arrival window, waits and delay allowance.":"Outside your preferences: "+String.join(" · ",notes)+".";
    }
    public static long waitExcess(CommuteForm f,Option o) {
        var s=summarize(f,o);
        return Math.max(0,s.firstWait()-f.maxBoardingWait())+Math.max(0,s.transferWait()-f.maxTransferWait());
    }
}
