package com.victorpena.just_in_transit;
import org.springframework.stereotype.Service;
@Service
public class TripHistory {
    private final TripReportRepository records;
    public TripHistory(TripReportRepository records) { this.records=records; }
    public void complete(ActiveJourney.State state) {
        if(state.phase()!=ActiveJourney.Phase.ARRIVED || !state.facts().keepHistory() || records.existsById(state.facts().id()))return;
        var facts=state.facts();var report=new TripReport();report.id=facts.id();report.corridor=state.form().corridor();report.travelDate=state.form().date();
        report.plannedDeparture=facts.plannedDeparture();report.actualDeparture=facts.confirmations().get("leftOrigin");
        report.plannedArrival=facts.plannedArrival();report.actualArrival=facts.confirmations().get("ARRIVED");
        report.transferOutcome=facts.confirmations().containsKey("missedTransfer")?"Missed selected transfer; completed on replacement":
            facts.confirmations().containsKey("ON_93")?"Made selected transfer (confirmed)":"Not recorded";
        records.save(report);
    }
}
