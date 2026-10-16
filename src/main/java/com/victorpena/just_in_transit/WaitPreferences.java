package com.victorpena.just_in_transit;

import java.time.*;
import static com.victorpena.just_in_transit.TransitModels.*;

/** Waiting excludes walking; limits are preferences, with explicit fallback labels. */
public final class WaitPreferences {
    private WaitPreferences() { }
    public static long firstSeconds(CommuteForm form,Leg first,Instant now) {
        return form.onboard()?0:form.atStop()?Math.max(0,Duration.between(now,first.board().at()).getSeconds()):form.boardingBuffer()*60L;
    }
    public static boolean fits(CommuteForm f,long first,long transfer,long arrival) {
        return (f.onboard() || first>=f.boardingBuffer()*60L && first<=f.maxBoardingWait()*60L)
            && transfer>=f.transferBuffer()*60L && transfer<=f.maxTransferWait()*60L
            && CommuteTiming.arrivalFits(f,arrival);
    }
    public static String rating(CommuteForm f,long first,long transfer,long arrival) {
        if(!f.departing() && arrival<0)return "Late arrival";
        if(!f.departing() && arrival>f.maxEarlyArrival()*60L)return "Earlier than your limit";
        if(transfer<0)return "Connection at risk";
        if(first>f.maxBoardingWait()*60L)return "Long first-stop wait";
        if(transfer>f.maxTransferWait()*60L)return "Long transfer wait";
        if(!f.onboard() && first<f.boardingBuffer()*60L)return "Tight boarding buffer";
        boolean t=transfer<f.transferBuffer()*60L,a=!f.departing() && arrival<f.arrivalBuffer()*60L;
        return t&&a?"Tight transfer + arrival":t?"Tight transfer":a?"Tight arrival":"Buffers met";
    }
}
