package com.victorpena.just_in_transit;

import static org.junit.jupiter.api.Assertions.*;
import static com.victorpena.just_in_transit.PlannerTests.*;
import com.google.transit.realtime.GtfsRealtime.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;

class AlertTests {
    private AlertStore.Context context() {
        return new AlertStore.Context("93","b","0","20261012","07:55:00","VIA",3,Set.of("76773","83463"),NOW,NOW.plusSeconds(3600));
    }
    private TranslatedString text(String text) {
        return TranslatedString.newBuilder().addTranslation(TranslatedString.Translation.newBuilder().setText(text).setLanguage("en")).build();
    }
    private Alert.Builder alert() {
        return Alert.newBuilder().setHeaderText(text("Stop closure")).setDescriptionText(text("Use the next stop."))
            .setEffect(Alert.Effect.STOP_MOVED).addInformedEntity(EntitySelector.newBuilder().setRouteId("93").setStopId("83463"));
    }
    private AlertStore.Snapshot snapshot(Alert.Builder alert) throws Exception {
        return AlertStore.Snapshot.parse(FeedMessage.newBuilder()
            .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(NOW.getEpochSecond()))
            .addEntity(FeedEntity.newBuilder().setId("one").setAlert(alert)).build().toByteArray());
    }
    @Test void selectorFieldsMustAllMatchAndDifferentSelectorsAreAlternatives() throws Exception {
        var good=alert();assertEquals(1,snapshot(good).relevant(List.of(context())).size());
        good.clearInformedEntity().addInformedEntity(EntitySelector.newBuilder().setRouteId("24").setStopId("83463"));
        assertTrue(snapshot(good).relevant(List.of(context())).isEmpty());
        good.addInformedEntity(EntitySelector.newBuilder().setAgencyId("VIA"));
        assertEquals(1,snapshot(good).relevant(List.of(context())).size());
    }
    @Test void expiredAlertsAndAlertsAfterTripAreExcluded() throws Exception {
        assertTrue(snapshot(alert().addActivePeriod(TimeRange.newBuilder().setEnd(NOW.getEpochSecond()))).relevant(List.of(context())).isEmpty());
        assertTrue(snapshot(alert().addActivePeriod(TimeRange.newBuilder().setStart(NOW.plusSeconds(3601).getEpochSecond()))).relevant(List.of(context())).isEmpty());
    }
    @Test void tripDateAndDirectionAreRespected() throws Exception {
        var a=alert().clearInformedEntity().addInformedEntity(EntitySelector.newBuilder().setRouteId("93").setDirectionId(1));
        assertTrue(snapshot(a).relevant(List.of(context())).isEmpty());
        a.clearInformedEntity().addInformedEntity(EntitySelector.newBuilder().setTrip(TripDescriptor.newBuilder().setTripId("b").setStartDate("20261013")));
        assertTrue(snapshot(a).relevant(List.of(context())).isEmpty());
    }
    @Test void staleAlertSnapshotIsNotPresentedAsConfirmed() throws Exception {
        assertFalse(snapshot(alert()).fresh(NOW.plusSeconds(601)));
        assertFalse(AlertStore.Snapshot.empty().fresh(NOW));
    }
    @Test void unsafeNoticeLinksAreOmittedAndEnglishTextPreferred() throws Exception {
        var a=alert().setUrl(text("javascript:alert(1)")).setHeaderText(TranslatedString.newBuilder()
            .addTranslation(TranslatedString.Translation.newBuilder().setText("Aviso").setLanguage("es"))
            .addTranslation(TranslatedString.Translation.newBuilder().setText("Advisory").setLanguage("en")));
        var notice=snapshot(a).relevant(List.of(context())).get(0);
        assertNull(notice.url());assertEquals("Advisory",notice.title());
        assertEquals("https://www.viainfo.net/",AlertStore.safeUrl("https://www.viainfo.net/"));
    }
    @Test void differentialFeedIsRejectedAndDeletedAlertsIgnored() throws Exception {
        var feed=FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(NOW.getEpochSecond()))
            .addEntity(FeedEntity.newBuilder().setId("deleted").setIsDeleted(true).setAlert(alert()));
        assertTrue(AlertStore.Snapshot.parse(feed.build().toByteArray()).relevant(List.of(context())).isEmpty());
        feed.getHeaderBuilder().setIncrementality(FeedHeader.Incrementality.DIFFERENTIAL);
        assertThrows(IOException.class,()->AlertStore.Snapshot.parse(feed.build().toByteArray()));
    }
}
