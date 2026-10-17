package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.time.*;
import java.util.*;

/** Ordered, boardable legs; shared by the personal planner and the older workflow. */
final class ScheduleLegs {
    private ScheduleLegs() { }
    static List<Leg> between(Schedule schedule, LiveSnapshot live, String route, String from,
                             String to, LocalDate date, Instant now) {
        List<Leg> result = new ArrayList<>();
        for (var day : List.of(date.minusDays(1), date)) for (var trip : schedule.trips()) {
            if (!trip.route().equals(route) || !schedule.active(trip.service(), day)) continue;
            StopTime start = null;
            for (var stop : trip.stops()) {
                if (start == null && stop.id().equals(from) && stop.pickup()) start = stop;
                else if (start != null && stop.id().equals(to) && stop.dropoff()) {
                    Event board = live.event(trip, day, start, true, now);
                    Event alight = live.event(trip, day, stop, false, now);
                    if (board.usable() && alight.usable() && !alight.at().isBefore(board.at()))
                        result.add(new Leg(route, trip.id(), board, alight, day));
                    break;
                }
            }
        }
        result.sort(Comparator.comparing(l -> l.board().at()));
        return result;
    }
}
