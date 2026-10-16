package com.victorpena.just_in_transit;
import org.springframework.stereotype.Service;
@Service
public class TripHistory {
    private final TripReportRepository records;
    public TripHistory(TripReportRepository records) { this.records=records; }
    public void complete(ActiveJourney.State state,String owner) {
        if(state.phase()!=ActiveJourney.Phase.ARRIVED)return;
        update(state,owner,true);
    }
    public void remove(String id,String owner) { records.deleteByIdAndOwner(id,owner); }
    public void update(ActiveJourney.State state,String owner,boolean ended) {
        if(state==null || !state.facts().keepHistory())return;
        var facts=state.facts();var report=records.findByIdAndOwner(facts.id(),owner).orElseGet(TripReport::new);report.id=facts.id();report.owner=owner;report.corridor=state.form().corridor();report.travelDate=state.form().date();
        report.status=state.phase()==ActiveJourney.Phase.ARRIVED?"Arrived":ended?"Ended before arrival":"In progress";
        report.plannedDeparture=facts.plannedDeparture();report.actualDeparture=facts.confirmations().get("leftOrigin");
        report.plannedArrival=facts.plannedArrival();report.actualArrival=facts.confirmations().get("ARRIVED");
        report.transferOutcome=facts.confirmations().containsKey("missedTransfer")?(facts.confirmations().containsKey("ON_93")?"Missed selected transfer; boarded replacement":"Missed selected transfer; replacement not confirmed"):
            facts.confirmations().containsKey("ON_93")?"Made selected transfer (confirmed)":"Not recorded";
        var labels=new java.util.LinkedHashMap<String,String>();
        labels.put("ON_24","Board first bus");labels.put("TRANSFERRING","Leave first bus");labels.put("ON_93","Board second bus");
        labels.put("onFirstConfirmed","Already on first bus (confirmation time)");labels.put("onSecondConfirmed","Already on second bus (confirmation time)");
        labels.put("WALKING_TO_DESTINATION","Leave second bus");labels.put("ARRIVED","Reach destination");
        var rows=labels.entrySet().stream().map(e->new TripReport.Timing(e.getValue(),facts.predictions().get(e.getKey()),facts.predictionSources().getOrDefault(e.getKey(),"Not recorded"),facts.confirmations().get(e.getKey()))).toList();
        report.timingJson=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(rows);
        report.changedBuses=facts.confirmations().containsKey("missedFirst") || facts.confirmations().containsKey("missedTransfer") || facts.confirmations().containsKey("changedVehicle") || facts.confirmations().containsKey("destinationChanged");
        records.save(report);
    }
}
