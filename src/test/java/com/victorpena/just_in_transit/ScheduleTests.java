package com.victorpena.just_in_transit;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

class ScheduleTests {
    @Test void acceptsViaSpacePaddedSingleDigitHours() {
        assertEquals(18240,ScheduleStore.seconds(" 5:04:00"));
    }
    @Test void parsesQuotedNamesExtendedHoursAndCalendarOverrides() throws Exception {
        var files=Map.of(
            "stops.txt","stop_id,stop_name,stop_lat,stop_lon\n81799,\"HOUSTON, MITTMAN\",29.4,-98.4\n76779,MARTIN,29.4,-98.5\n",
            "trips.txt","route_id,service_id,trip_id,trip_headsign\n24,weekday,a,Downtown\n",
            "stop_times.txt","trip_id,arrival_time,departure_time,stop_id,stop_sequence\na,25:10:00,25:10:00,76779,2\na,25:00:00,25:00:00,81799,1\n",
            "calendar.txt","service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nweekday,1,1,1,1,1,0,0,20261001,20261031\n",
            "calendar_dates.txt","service_id,date,exception_type\nweekday,20261012,2\nweekday,20261011,1\n");
        var out=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(out)) { for(var e:files.entrySet()) { zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry(); } }
        var s=ScheduleStore.parse(out.toByteArray());
        assertEquals("HOUSTON, MITTMAN",s.stops().get("81799").name());
        assertEquals(90000,s.trips().get(0).stops().get(0).arrival());
        assertFalse(s.active("weekday",LocalDate.of(2026,10,12)));
        assertTrue(s.active("weekday",LocalDate.of(2026,10,11)));
        assertFalse(s.active("weekday",LocalDate.of(2026,11,2)));
    }
    @Test void refusesIncompleteZip() throws Exception {
        var bytes=new ByteArrayOutputStream();try(var ignored=new ZipOutputStream(bytes)) { }
        assertThrows(IOException.class,()->ScheduleStore.parse(bytes.toByteArray()));
    }
}
