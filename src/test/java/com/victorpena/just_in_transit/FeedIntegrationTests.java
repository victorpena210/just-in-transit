package com.victorpena.just_in_transit;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Optional check against downloaded VIA files; ordinary tests remain completely offline. */
@EnabledIfSystemProperty(named="via.integration.dir",matches=".+")
class FeedIntegrationTests {
    @Test void readsRealScheduleAndTripUpdateSnapshot() throws Exception {
        Path dir=Path.of(System.getProperty("via.integration.dir"));
        var schedule=ScheduleStore.parse(Files.readAllBytes(dir.resolve("google_transit.zip")));
        var live=RealtimeStore.Snapshot.parse(Files.readAllBytes(dir.resolve("tripupdates.pb")));
        assertTrue(schedule.stops().containsKey("81799"));assertTrue(schedule.stops().containsKey("76773"));
        assertTrue(schedule.trips().stream().anyMatch(t->t.route().equals("24")));
        assertTrue(schedule.trips().stream().anyMatch(t->t.route().equals("93")));
        assertFalse(live.trips().isEmpty());
        var date=live.timestamp().atZone(TransitModels.ZONE).toLocalDate();
        var board=new CommutePlanner().board(schedule,live,live.timestamp());
        assertFalse(board.isEmpty(),"Expected buses within two hours of the supplied daytime snapshot");
        System.out.println("Real VIA snapshot: "+live.trips().size()+" trip updates; "+schedule.trips().size()+" scheduled 24/93 trips; "+board.stream().filter(b->b.event().live()).count()+" live boarding events at the snapshot time on "+date);
    }
}
