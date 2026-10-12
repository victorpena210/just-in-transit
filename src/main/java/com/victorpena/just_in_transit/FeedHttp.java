package com.victorpena.just_in_transit;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public final class FeedHttp {
    private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private FeedHttp() { }
    public static byte[] fetch(String url,int maxBytes) throws IOException,InterruptedException {
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25))
                .header("User-Agent","JustInTransit/0.1 (personal commute planner)").GET().build();
        // ofByteArray keeps the request timeout active through body receipt.
        var response=CLIENT.send(request,HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()!=200) throw new IOException("Feed HTTP "+response.statusCode());
        if(response.body().length==0 || response.body().length>maxBytes) throw new IOException("Feed size out of bounds");
        return response.body();
    }
}
