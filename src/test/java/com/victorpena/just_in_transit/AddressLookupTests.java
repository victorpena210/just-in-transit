package com.victorpena.just_in_transit;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

class AddressLookupTests {
    private HttpServer server;
    private String base;
    private int addressStatus=200,photonStatus=200,addressCalls,photonCalls;
    private String addressQuery,addressBody=matches(match("600 SOLEDAD ST, SAN ANTONIO, TX, 78205","TX","-98.493319","29.431097"));
    private String photonBody="""
        {"features":[{"properties":{"housenumber":"600","street":"Soledad St","city":"San Antonio"},
        "geometry":{"coordinates":[-98.493319,29.431097]}}]}
        """;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/address",exchange->{
            addressCalls++;addressQuery=URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8);
            var bytes=addressBody.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(addressStatus,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.createContext("/photon",exchange->{
            photonCalls++;var bytes=photonBody.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(photonStatus,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.start();base="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }
    private MapService service(boolean enabled,boolean addresses) {
        return new MapService(base+"/photon",base+"/walk","test",enabled,base+"/address",addresses);
    }
    private static String match(String label,String state,String lon,String lat) {
        return "{\"matchedAddress\":\""+label+"\",\"addressComponents\":{\"state\":\""+state
            +"\"},\"coordinates\":{\"x\":"+lon+",\"y\":"+lat+"}}";
    }
    private static String matches(String... items) { return "{\"result\":{\"addressMatches\":["+String.join(",",items)+"]}}"; }
    @Test void numberedLookupUsesAddressRangesAndCacheBeforePlaceSearch() throws Exception {
        var maps=service(true,true);var places=maps.search("600 soledad st");
        assertEquals(1,places.size());var p=places.get(0);
        assertEquals("600",p.houseNumber());assertTrue(p.needsConfirmation());assertFalse(p.approximate());
        assertEquals("U.S. Census address ranges",p.source());assertEquals(29.431097,p.lat(),.000001);
        assertTrue(addressQuery.contains("address=600 soledad st, San Antonio, TX"));
        assertTrue(addressQuery.contains("benchmark=Public_AR_Current"));
        assertEquals(places,maps.search("600 soledad st"));assertEquals(1,addressCalls);assertEquals(0,photonCalls);
    }
    @Test void incorrectHouseNumbersStatesCoordinatesAndDuplicatesAreExcluded() throws Exception {
        String good=match("600 SOLEDAD ST, SAN ANTONIO, TX, 78205","TX","-98.493319","29.431097");
        var bytes=matches(good,good,
            match("601 SOLEDAD ST, SAN ANTONIO, TX","TX","-98.49","29.43"),
            match("600 SOLEDAD ST, SAN ANTONIO, TX","CA","-98.49","29.43"),
            match("600 SOLEDAD ST, SAN ANTONIO, TX","TX","-76.9","38.8"),
            match("600 SOLEDAD ST, SAN ANTONIO, TX","TX","\"not a number\"","29.43")).getBytes(StandardCharsets.UTF_8);
        assertEquals(1,MapService.parseAddresses(bytes,"600 soledad st").size());
        assertTrue(MapService.parseAddresses(bytes,"Central Library").isEmpty());
    }
    @Test void malformedAndErrorResponsesAreNotEmptySuccesses() {
        for(String json:List.of("{}","{\"errors\":[\"unavailable\"]}","{broken"))
            assertThrows(IOException.class,()->MapService.parseAddresses(json.getBytes(StandardCharsets.UTF_8),"600 soledad st"));
    }
    @Test void addsLocalContextWithoutOverwritingExplicitCitiesOrZipCodes() {
        assertEquals("600 soledad st, San Antonio, TX",MapService.localQuery(" 600  soledad st "));
        assertEquals("600 soledad st san antonio, TX",MapService.localQuery("600 soledad st san antonio"));
        assertEquals("600 Soledad St, San Antonio, TX",MapService.localQuery("600 Soledad St, San Antonio, TX"));
        assertEquals("123 Main St, Austin, TX",MapService.localQuery("123 Main St, Austin, TX"));
        assertEquals("600 soledad st 78205",MapService.localQuery("600 soledad st 78205"));
    }
    @Test void addressServiceFailureCanStillReturnNumberedPhotonMatch() throws Exception {
        addressStatus=503;
        var places=service(true,true).search("600 Soledad St");assertEquals(1,places.size());
        assertFalse(places.get(0).needsConfirmation());assertEquals("OpenStreetMap / Photon",places.get(0).source());
        assertEquals(1,photonCalls);
    }
    @Test void noAddressMatchFallsBackToStreetResultsWithoutMakingThemExact() throws Exception {
        addressBody=matches();photonBody="""
            {"features":[{"properties":{"name":"Soledad St","type":"street"},"geometry":{"coordinates":[-98.49,29.43]}}]}
            """;
        var places=service(true,true).search("600 Soledad St");assertTrue(places.get(0).approximate());
        assertEquals(1,addressCalls);assertEquals(1,photonCalls);
    }
    @Test void providerOutageIsNotReportedAsAHouseNumberMiss() {
        addressStatus=503;photonBody="{\"features\":[]}";
        assertThrows(IOException.class,()->service(true,true).search("600 Soledad St"));
    }
    @Test void placeNamesSkipCensusAndAdministratorCanDisableIt() throws Exception {
        service(true,true).search("Central Library");assertEquals(0,addressCalls);assertEquals(1,photonCalls);
        service(true,false).search("600 Soledad St");assertEquals(0,addressCalls);assertEquals(2,photonCalls);
    }
    @Test void disabledMapsSendNothingToEitherProvider() {
        assertThrows(IOException.class,()->service(false,true).search("600 Soledad St"));
        assertEquals(0,addressCalls);assertEquals(0,photonCalls);
    }
}
