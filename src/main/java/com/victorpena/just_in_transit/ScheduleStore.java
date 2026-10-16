package com.victorpena.just_in_transit;

import static com.victorpena.just_in_transit.TransitModels.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;
import org.apache.commons.csv.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ScheduleStore {
    private final String url;
    private final Path cache;
    private final boolean enabled;
    private Schedule schedule;
    private Instant loaded=Instant.EPOCH,attempted=Instant.EPOCH;
    private String message="Schedule has not loaded yet.";
    public ScheduleStore(@Value("${via.schedule-url}") String url,@Value("${via.cache-dir}") String dir,
                         @Value("${via.enabled:true}") boolean enabled) {
        this.url=url; cache=Path.of(dir,"via-schedule.zip");this.enabled=enabled;
    }
    public synchronized Schedule get() {
        Instant now=Instant.now();
        if(!enabled) return schedule;
        if(schedule!=null && loaded.plusSeconds(21600).isAfter(now)) return schedule;
        if(attempted.plusSeconds(60).isAfter(now))return schedule;
        attempted=now;
        try {
            byte[] bytes=FeedHttp.fetch(url,30_000_000);
            Schedule next=parse(bytes); schedule=next;loaded=now;message="VIA schedule loaded.";
            try {
                Files.createDirectories(cache.getParent());
                Path tmp=Files.createTempFile(cache.getParent(),"via-schedule-",".tmp");
                Files.write(tmp,bytes);Files.move(tmp,cache,StandardCopyOption.REPLACE_EXISTING);
            }catch(IOException ignored) { message="Schedule loaded; disk cache unavailable."; }
        } catch(Exception e) {
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            org.slf4j.LoggerFactory.getLogger(ScheduleStore.class).warn("VIA schedule could not be loaded",e);
            message="Schedule download unavailable.";
            if(schedule==null && Files.isRegularFile(cache)) {
                try { schedule=parse(Files.readAllBytes(cache));message="Using previously downloaded VIA schedule."; }
                catch(Exception ignored) { message="Schedule unavailable. Try again shortly."; }
            } else if(schedule!=null) message="Using previously downloaded VIA schedule.";
        }
        return schedule;
    }
    public String message() { return message; }
    public static Schedule parse(byte[] bytes) throws IOException {
        Map<String,byte[]> files=new HashMap<>();
        Set<String> wanted=Set.of("stops.txt","trips.txt","stop_times.txt","calendar.txt","calendar_dates.txt","routes.txt","agency.txt","transfers.txt");
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                if(wanted.contains(entry.getName())) {
                    byte[] data=zip.readNBytes(150_000_001);
                    if(data.length>150_000_000)throw new IOException("Schedule entry too large");
                    files.put(entry.getName(),data);
                }
            }
        }
        if(!files.keySet().containsAll(Set.of("stops.txt","trips.txt","stop_times.txt")))throw new IOException("Incomplete schedule");
        Map<String,RouteInfo> routes=new HashMap<>();
        Map<String,Stop> stops=new HashMap<>();Map<String,Trip> trips=new HashMap<>();
        Map<String,Service> services=new HashMap<>();Map<String,Map<LocalDate,Boolean>> exceptions=new HashMap<>();
        String soleAgency="";
        if(files.containsKey("agency.txt"))try(var csv=csv(files.get("agency.txt"))) {
            var agencies=csv.getRecords();if(agencies.size()==1)soleAgency=get(agencies.get(0),"agency_id","");
        }
        if(files.containsKey("routes.txt"))try(var csv=csv(files.get("routes.txt"))) {
            for(var r:csv)routes.put(r.get("route_id"),new RouteInfo(get(r,"agency_id",soleAgency),Integer.parseInt(get(r,"route_type","3"))));
        }
        try(var csv=csv(files.get("stops.txt"))) {
            for(var r:csv)stops.put(r.get("stop_id"),new Stop(r.get("stop_id"),r.get("stop_name"),
                    Double.parseDouble(r.get("stop_lat")),Double.parseDouble(r.get("stop_lon"))));
        }
        try(var csv=csv(files.get("trips.txt"))) {
            for(var r:csv)trips.put(r.get("trip_id"),
                    new Trip(r.get("trip_id"),r.get("route_id"),r.get("service_id"),get(r,"trip_headsign",""),new ArrayList<>(),get(r,"direction_id","")));
        }
        try(var csv=csv(files.get("stop_times.txt"))) {
            for(var r:csv) {
                Trip trip=trips.get(r.get("trip_id"));if(trip==null)continue;
                String arrival=r.get("arrival_time"),departure=r.get("departure_time");
                if(arrival.isBlank() || departure.isBlank())continue;
                trip.stops().add(new StopTime(r.get("stop_id"),Integer.parseInt(r.get("stop_sequence")),seconds(arrival),seconds(departure),
                        get(r,"pickup_type","0").equals("0"),get(r,"drop_off_type","0").equals("0")));
            }
        }
        for(var trip:trips.values())trip.stops().sort(Comparator.comparingInt(StopTime::sequence));
        if(files.containsKey("calendar.txt"))try(var csv=csv(files.get("calendar.txt"))) {
            for(var r:csv) {
                Set<DayOfWeek> days=EnumSet.noneOf(DayOfWeek.class);
                for(var day:DayOfWeek.values())if(r.get(day.name().toLowerCase(Locale.ROOT)).equals("1"))days.add(day);
                services.put(r.get("service_id"),new Service(date(r.get("start_date")),date(r.get("end_date")),days));
            }
        }
        if(files.containsKey("calendar_dates.txt"))try(var csv=csv(files.get("calendar_dates.txt"))) {
            for(var r:csv)exceptions.computeIfAbsent(r.get("service_id"),k->new HashMap<>()).put(date(r.get("date")),r.get("exception_type").equals("1"));
        }
        List<Transfer> transfers=new ArrayList<>();
        if(files.containsKey("transfers.txt"))try(var csv=csv(files.get("transfers.txt"))) {
            for(var row:csv)transfers.add(new Transfer(row.get("from_stop_id"),row.get("to_stop_id"),Integer.parseInt(get(row,"transfer_type","0")),
                Integer.parseInt(get(row,"min_transfer_time","0")),get(row,"from_route_id",""),get(row,"to_route_id",""),get(row,"from_trip_id",""),get(row,"to_trip_id","")));
        }
        if(trips.isEmpty() || services.isEmpty() && exceptions.isEmpty())throw new IOException("Missing route/service data");
        return new Schedule(Map.copyOf(stops),List.copyOf(trips.values()),Map.copyOf(services),Map.copyOf(exceptions),Map.copyOf(routes),List.copyOf(transfers));
    }
    private static CSVParser csv(byte[] bytes) throws IOException {
        String text=new String(bytes,StandardCharsets.UTF_8);
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).get().parse(new StringReader(text));
    }
    private static String get(CSVRecord r,String field,String fallback) {
        return !r.isMapped(field) || r.get(field).isBlank()?fallback:r.get(field);
    }
    public static int seconds(String s) {
        String[] p=s.trim().split(":");return Integer.parseInt(p[0])*3600+Integer.parseInt(p[1])*60+Integer.parseInt(p[2]);
    }
    private static LocalDate date(String s) { return LocalDate.parse(s,DateTimeFormatter.BASIC_ISO_DATE); }
}
