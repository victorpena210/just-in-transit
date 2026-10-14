package com.victorpena.just_in_transit;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit, low-volume lookups only; no address requests during planning or auto refresh. */
@Service
public class MapService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String searchUrl,walkUrl,clientId,addressUrl;
    private final boolean addressEnabled;
    private static final Pattern NUMBER=Pattern.compile("^(\\d+[A-Za-z]?(?:-\\d+[A-Za-z]?)?)\\b");
    private final boolean enabled;
    private final Map<String,Cached> cache=new LinkedHashMap<>();
    private final Map<String,Instant> lastRequest=new HashMap<>();
    private record Cached(Instant at,byte[] bytes) { }
    public MapService(@Value("${maps.search-url:https://photon.komoot.io/api/}") String searchUrl,
                      @Value("${maps.walk-url:https://valhalla1.openstreetmap.de/route}") String walkUrl,
                      @Value("${maps.client-id:just-in-transit-local}") String clientId,
                      @Value("${maps.enabled:true}") boolean enabled,
                      @Value("${maps.address-url:https://geocoding.geo.census.gov/geocoder/locations/onelineaddress}") String addressUrl,
                      @Value("${maps.address-enabled:true}") boolean addressEnabled) {
        this.searchUrl=searchUrl;this.walkUrl=walkUrl;this.clientId=clientId;this.enabled=enabled;
        this.addressUrl=addressUrl;this.addressEnabled=addressEnabled;
    }
    public List<Place> search(String query) throws IOException,InterruptedException {
        if(!enabled)throw new IOException("Map lookups are disabled.");
        String normalized=localQuery(query);
        IOException addressFailure=null;
        // Census matches house-number ranges that may be absent from OpenStreetMap.
        // These are estimated street positions and ALWAYS require map confirmation.
        if(addressEnabled && !addressUrl.isBlank() && !houseNumber(query).isBlank() && normalized.length()<=100) {
            try {
                var matches=parseAddresses(fetch(addressUrl+"?address="+encode(normalized)
                    +"&benchmark=Public_AR_Current&format=json","address"),query);
                if(!matches.isEmpty())return matches;
            }catch(IOException error) { addressFailure=error; }
        }
        String url=searchUrl+"?q="+encode(normalized)+"&limit=5&lang=en&lat=29.4252&lon=-98.4946&bbox=-99.1,29.1,-98.0,29.9";
        var places=parsePlaces(fetch(url,"search"));
        // Avoid reporting a house-number miss if the address service never answered.
        if(addressFailure!=null && places.stream().noneMatch(p->!p.approximate()
            && houseNumber(query).equalsIgnoreCase(p.houseNumber())))throw addressFailure;
        return places;
    }
    static String houseNumber(String value) {
        var matcher=NUMBER.matcher(value.strip());return matcher.find()?matcher.group(1):"";
    }
    static String localQuery(String query) {
        String value=query.strip().replaceAll("\\s+"," ");
        if(value.matches("(?i).*\\bSAN ANTONIO\\b.*")) {
            if(!value.matches("(?i).*\\b(?:TX|TEXAS)\\b.*"))value+=", TX";
        } else if(!value.contains(",") && !value.matches("(?i).*\\b(?:TX|TEXAS|[0-9]{5}(?:-[0-9]{4})?)\\b.*")) {
            value+=", San Antonio, TX";
        }
        return value;
    }
    static List<Place> parseAddresses(byte[] bytes,String query) throws IOException {
        try {
            JsonNode matches=JSON.readTree(bytes).path("result").path("addressMatches");
            if(!matches.isArray())throw new IOException("Address response could not be read");
            var places=new ArrayList<Place>();var seen=new HashSet<String>();
            String requested=houseNumber(query);if(requested.isBlank())return List.of();
            for(JsonNode match:matches) {
                String label=match.path("matchedAddress").asText("").strip();
                JsonNode coordinates=match.path("coordinates");
                if(label.isBlank() || label.length()>200 || !requested.equalsIgnoreCase(houseNumber(label))
                    || !coordinates.path("x").isNumber() || !coordinates.path("y").isNumber()
                    || !"TX".equalsIgnoreCase(match.path("addressComponents").path("state").asText("")))continue;
                double lat=coordinates.path("y").asDouble(),lon=coordinates.path("x").asDouble();
                if(!inArea(lat,lon) || !seen.add(label.toLowerCase(Locale.ROOT)+":"+lat+":"+lon))continue;
                places.add(new Place(label,lat,lon,houseNumber(label),false,"U.S. Census address ranges",true));
                if(places.size()==5)break;
            }
            return List.copyOf(places);
        }catch(RuntimeException error) { throw new IOException("Address response could not be read"); }
    }
    public Walk walk(double fromLat,double fromLon,double toLat,double toLon) throws IOException,InterruptedException {
        var payload=Map.of("locations",List.of(Map.of("lat",fromLat,"lon",fromLon),Map.of("lat",toLat,"lon",toLon)),
            "costing","pedestrian","costing_options",Map.of("pedestrian",Map.of("walking_speed",4.5)),"units","kilometers");
        return parseWalk(fetch(walkUrl+"?json="+encode(JSON.writeValueAsString(payload)),"walk"));
    }
    private synchronized byte[] fetch(String url,String provider) throws IOException,InterruptedException {
        if(!enabled)throw new IOException("Map lookups are disabled.");
        Instant now=Instant.now();
        cache.entrySet().removeIf(e->e.getValue().at().plusSeconds(86400).isBefore(now));
        var previous=cache.get(url);if(previous!=null)return previous.bytes();
        if(lastRequest.getOrDefault(provider,Instant.EPOCH).plusMillis(1100).isAfter(now))
            throw new BusyException();
        lastRequest.put(provider,now);
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds("walk".equals(provider)?10:8))
            .header("User-Agent","JustInTransit/0.2 (personal commute planner)")
            .header("X-Client-Id",clientId).header("Accept","application/json").GET().build();
        var response=HTTP.send(request,HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()==429)throw new BusyException();
        if(response.statusCode()!=200 || response.body().length>2_000_000)throw new IOException("Map lookup unavailable");
        // Do not log URLs, queries, coordinates, or response bodies.
        if(cache.size()>=256)cache.remove(cache.keySet().iterator().next());
        cache.put(url,new Cached(now,response.body()));return response.body();
    }
    public synchronized void clearCache() { cache.clear(); }
    static List<Place> parsePlaces(byte[] bytes) throws IOException {
        try {
            List<Place> result=new ArrayList<>();
            for(JsonNode f:JSON.readTree(bytes).path("features")) {
                JsonNode coords=f.path("geometry").path("coordinates"),p=f.path("properties");
                if(coords.size()<2 || !coords.get(0).isNumber() || !coords.get(1).isNumber())continue;
                double lon=coords.get(0).asDouble(),lat=coords.get(1).asDouble();
                if(!inArea(lat,lon))continue;
                var parts=new LinkedHashSet<String>();
                String name=p.path("name").asText("");if(!name.isBlank())parts.add(name);
                String street=(p.path("housenumber").asText("")+" "+p.path("street").asText("")).strip();
                if(!street.isBlank())parts.add(street);
                for(String field:List.of("city","state","postcode")) {
                    String value=p.path(field).asText("");if(!value.isBlank())parts.add(value);
                }
                String label=String.join(", ",parts);if(label.isBlank())continue;
                String houseNumber=p.path("housenumber").asText("");
                String type=p.path("type").asText("");
                boolean broad=houseNumber.isBlank() && (Set.of("street","city","district","locality","county","state","country").contains(type)
                    || "highway".equals(p.path("osm_key").asText("")) || name.isBlank() || name.equalsIgnoreCase(p.path("street").asText("")));
                result.add(new Place(label.length()>200?label.substring(0,200):label,lat,lon,houseNumber,broad));
                if(result.size()==5)break;
            }
            return List.copyOf(result);
        }catch(RuntimeException e) { throw new IOException("Address response could not be read"); }
    }
    static Walk parseWalk(byte[] bytes) throws IOException {
        try {
            JsonNode trip=JSON.readTree(bytes).path("trip"),summary=trip.path("summary");
            double seconds=summary.path("time").asDouble(-1),km=summary.path("length").asDouble(-1);
            if(trip.path("status").asInt(-1)!=0 || !Double.isFinite(seconds) || !Double.isFinite(km)
                || seconds<0 || seconds>7200 || km<0 || km>10)throw new IOException("No usable pedestrian route");
            return new Walk((int)Math.ceil(seconds/60),Math.round(km*1000),"Pedestrian route · 4.5 km/h walking estimate");
        }catch(RuntimeException e) { throw new IOException("Walking response could not be read"); }
    }
    static boolean inArea(double lat,double lon) { return Double.isFinite(lat)&&Double.isFinite(lon)&&lat>=29.1&&lat<=29.9&&lon>=-99.1&&lon<=-98.0; }
    private static String encode(String text) { return URLEncoder.encode(text,StandardCharsets.UTF_8); }
    public record Place(String label,double lat,double lon,String houseNumber,boolean approximate,String source,boolean needsConfirmation) {
        public Place(String label,double lat,double lon,String houseNumber,boolean approximate) {
            this(label,lat,lon,houseNumber,approximate,"OpenStreetMap / Photon",false);
        }
    }
    public record Walk(int minutes,long meters,String source) { }
    static class BusyException extends IOException { }
}
