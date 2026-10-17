package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;
import java.util.*;

/** Victor's one commute. Timing limits are requirements, never ranking preferences. */
public final class PersonalCommutePlanner {
    public static final int HOME_WALK = 5, FIRST_STOP_WAIT = 3;
    public static final int TRANSFER_WALK = 3, TRANSFER_BUFFER = 3, MAX_TRANSFER_WAIT = 10;
    public static final String ORIGIN = "81799", TRANSFER_OFF = "76779",
        TRANSFER_ON = "76773", DESTINATION = "83463";

    public record Request(LocalDate date, LocalTime arriveBy, int finalWalk) {
        public Request {
            Objects.requireNonNull(date); Objects.requireNonNull(arriveBy);
            if (finalWalk < 0 || finalWalk > 60) throw new IllegalArgumentException("Final walk must be 0–60 minutes.");
        }
        public Instant deadline() { return date.atTime(arriveBy).atZone(ZONE).toInstant(); }
    }
    public record Plan(Leg first, Leg second, Instant leaveHome, Instant arrival,
                       long transferSeconds, long earlySeconds, int finalWalk) {
        public String leaveDisplay() { return time(leaveHome); }
        public String leaveDayDisplay() { return java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d").withZone(ZONE).format(leaveHome); }
        public String firstStopDisplay() { return time(leaveHome.plusSeconds(HOME_WALK * 60L)); }
        public String arrivalDisplay() { return time(arrival); }
        public String transferDisplay() { return duration(transferSeconds); }
        public String waitDisplay() { return duration(transferSeconds - TRANSFER_WALK * 60L); }
        public String earlyDisplay() { return earlySeconds == 0 ? "At your deadline" : duration(earlySeconds) + " before your deadline"; }
        public String dataLabel() {
            long live = List.of(first.board(), first.alight(), second.board(), second.alight()).stream().filter(Event::live).count();
            return live == 4 ? "Live estimates" : live == 0 ? "Scheduled times" : "Live + scheduled times";
        }
        public boolean directTransfer() { return first.alight().directPrediction() && second.board().directPrediction(); }
        public long leaveEpoch() { return leaveHome.toEpochMilli(); }
    }
    public record Result(Plan plan, String message) { }

    public Result plan(Request request, Schedule schedule, LiveSnapshot live, Instant now) {
        if (request.date().isBefore(now.atZone(ZONE).toLocalDate()))
            return new Result(null, "Choose today or a future travel date.");
        if (!request.deadline().isAfter(now))
            return new Result(null, "That arrival deadline has passed. Choose a later time or another day.");
        if (schedule == null)
            return new Result(null, "VIA's schedule is unavailable. Try again shortly.");
        if (live == null) live = LiveSnapshot.empty();
        List<Leg> first = ScheduleLegs.between(schedule, live, "24", ORIGIN, TRANSFER_OFF, request.date(), now);
        List<Leg> second = ScheduleLegs.between(schedule, live, "93", TRANSFER_ON, DESTINATION, request.date(), now);
        if (first.isEmpty() || second.isEmpty())
            return new Result(null, "No 24 → 93 service was found for this date in the loaded schedule. It may be outside the schedule's date range.");
        List<Plan> matches = new ArrayList<>();
        boolean connectionFits = false, onTimeButMissed = false;
        for (Leg a : first) for (Leg b : second) {
            // First match the transfer. Compare seconds so a 10:01 wait cannot pass as 10 minutes.
            long transfer = Duration.between(a.alight().at(), b.board().at()).getSeconds();
            if (transfer < (TRANSFER_WALK + TRANSFER_BUFFER) * 60L || transfer > (TRANSFER_WALK + MAX_TRANSFER_WAIT) * 60L) continue;
            // Then apply the deadline, including an optional walk after the employee entrance stop.
            Instant arrival = b.alight().at().plusSeconds(request.finalWalk() * 60L);
            // Previous service days are included only for trips actually arriving on the requested day.
            if (!arrival.atZone(ZONE).toLocalDate().equals(request.date())) continue;
            connectionFits = true;
            if (arrival.isAfter(request.deadline())) continue;
            // Finally work backward: five-minute walk plus three minutes at the first stop.
            Instant leave = a.board().at().minusSeconds((HOME_WALK + FIRST_STOP_WAIT) * 60L);
            if (leave.isBefore(now)) { onTimeButMissed = true; continue; }
            matches.add(new Plan(a, b, leave, arrival, transfer,
                Duration.between(arrival, request.deadline()).getSeconds(), request.finalWalk()));
        }
        // A shorter wait must not send Victor to work an hour early when a later valid trip exists.
        Plan best = matches.stream().sorted(Comparator.comparing(Plan::leaveHome).reversed()
            .thenComparing(Plan::arrival, Comparator.reverseOrder())
            .thenComparingLong(Plan::transferSeconds)
            .thenComparing(p -> p.first().tripId()).thenComparing(p -> p.second().tripId())).findFirst().orElse(null);
        if (best != null) return new Result(best, best.earlySeconds() >= 3600
            ? "The latest matching connection arrives " + duration(best.earlySeconds()) + " early. No later departure meets both your waiting limit and deadline."
            : "Latest departure that meets your transfer limit and arrival deadline.");
        if (onTimeButMissed) return new Result(null,
            "No matching connection remains: you would already need to have left home to arrive on time with a transfer wait of 10 minutes or less. Try a later arrival time.");
        return new Result(null, connectionFits
            ? "Connections within your transfer limit exist, but none reach Six Flags by your deadline. Try a later arrival time."
            : "No matching connection: the 24 and 93 do not allow a 3-minute walk followed by 3–10 minutes waiting on this date. Your waiting limit has not been relaxed.");
    }
    private static String duration(long seconds) {
        if (seconds >= 3600) return seconds / 3600 + " hr " + (seconds % 3600) / 60 + " min";
        return seconds % 60 == 0 ? seconds / 60 + " min" : seconds / 60 + " min " + seconds % 60 + " sec";
    }
}
