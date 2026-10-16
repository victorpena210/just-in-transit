package com.victorpena.just_in_transit;
import org.springframework.stereotype.Service;
@Service
public class TripHistory {
    private final TripReportRepository records;
    public TripHistory(TripReportRepository records) { this.records=records; }
    public void complete(ActiveJourney.State state,String owner) {
        if(state.phase()!=ActiveJourney.Phase.ARRIVED || !state.facts().keepHistory() || records.existsById(state.facts().id()))return;
        var facts=state.facts();var report=new TripReport();report.id=facts.id();report.owner=owner;report.corridor=state.form().corridor();report.travelDate=state.form().date();
        report.plannedDeparture=facts.plannedDeparture();report.actualDeparture=facts.confirmations().get("leftOrigin");
        report.plannedArrival=facts.plannedArrival();report.actualArrival=facts.confirmations().get("ARRIVED");
        report.transferOutcome=facts.confirmations().containsKey("missedTransfer")?"Missed selected transfer; completed on replacement":
            facts.confirmations().containsKey("ON_93")?"Made selected transfer (confirmed)":"Not recorded";
        var labels=new java.util.LinkedHashMap<String,String>();
        labels.put("ON_24","Board first bus");labels.put("TRANSFERRING","Leave first bus");labels.put("ON_93","Board second bus");
        labels.put("WALKING_TO_DESTINATION","Leave second bus");labels.put("ARRIVED","Reach destination");
        var rows=labels.entrySet().stream().map(e->new TripReport.Timing(e.getValue(),facts.predictions().get(e.getKey()),facts.predictionSources().getOrDefault(e.getKey(),"Not recorded"),facts.confirmations().get(e.getKey()))).toList();
        report.timingJson=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(rows);
        report.changedBuses=facts.confirmations().containsKey("missedFirst") || facts.confirmations().containsKey("missedTransfer");
        records.save(report);
    }
}
