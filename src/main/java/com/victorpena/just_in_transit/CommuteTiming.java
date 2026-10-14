package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;

/** Extra delay is a planning scenario, never an invented live prediction. */
public final class CommuteTiming {
    private CommuteTiming() { }
    public record Summary(long firstWait,long transferWait,long totalMinutes,long transferReserve,
                          String atFirstStop,String transferReady,String trafficArrival,
                          boolean fitsWindows,String trafficNote) { }
    public static Summary summarize(CommuteForm f,Option o) {
        Instant atStop=f.atStop() || f.onboard()?o.leaveHome():o.leaveHome().plusSeconds(f.walkToStop()*60L);
        long first=f.onboard()?0:Math.max(0,Duration.between(atStop,o.first().board().at()).toMinutes());
        long reserve=o.transferMargin()-f.trafficAllowance();
        boolean fits=(f.onboard() || first>=f.boardingBuffer() && first<=f.maxBoardingWait())
            && o.transferWait()>=f.transferBuffer() && o.transferWait()<=f.maxTransferWait();
        String traffic=f.trafficAllowance()==0?"No extra traffic allowance set. Times may change.":
            "Allows "+f.trafficAllowance()+" extra min on each bus beyond its current estimate. "
            +(reserve<0?"The transfer would be missed in this scenario.":"Arrival with the second-bus allowance: "+time(o.atWork().plusSeconds(f.trafficAllowance()*60L))+".");
        return new Summary(first,o.transferWait(),Math.max(0,Duration.between(o.leaveHome(),o.atWork()).toMinutes()),reserve,
            time(atStop),time(o.first().alight().at().plusSeconds(f.transferWalk()*60L)),time(o.atWork().plusSeconds(f.trafficAllowance()*60L)),fits,traffic);
    }
    public static boolean meets(CommuteForm f,long firstWaitSeconds,long transferSeconds,long arrivalSeconds) {
        return (f.onboard() || firstWaitSeconds>=f.boardingBuffer()*60L && firstWaitSeconds<=f.maxBoardingWait()*60L)
            && transferSeconds>=(f.transferBuffer()+f.trafficAllowance())*60L
            && transferSeconds<=f.maxTransferWait()*60L
            && (f.departing() || arrivalSeconds>=(f.arrivalBuffer()+f.trafficAllowance())*60L);
    }
    public static long waitExcess(CommuteForm f,Option o) {
        var s=summarize(f,o);
        return Math.max(0,s.firstWait()-f.maxBoardingWait())+Math.max(0,s.transferWait()-f.maxTransferWait());
    }
}
