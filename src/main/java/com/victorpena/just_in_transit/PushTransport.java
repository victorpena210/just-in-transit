package com.victorpena.just_in_transit;

import java.net.*;import java.net.http.*;import java.time.Duration;import java.security.Security;import java.util.*;
import org.springframework.beans.factory.annotation.Value;import org.springframework.stereotype.Service;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import nl.martijndwars.webpush.*;
import tools.jackson.databind.json.JsonMapper;

@Service public class PushTransport {
    private final String publicKey;private final Encoder service;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();
    public PushTransport(@Value("${jit.push.public-key:}") String publicKey,@Value("${jit.push.private-key:}") String privateKey,@Value("${jit.push.subject:}") String subject) {
        this.publicKey=publicKey;Encoder configured=null;
        if(!publicKey.isBlank() && !privateKey.isBlank() && (subject.startsWith("mailto:") || subject.startsWith("https://"))) {
            try { Security.addProvider(new BouncyCastleProvider());configured=new Encoder(publicKey,privateKey,subject); }
            catch(Exception e) { throw new IllegalStateException("Invalid push signing configuration",e); }
        }
        service=configured;
    }
    static class Encoder extends AbstractPushService<Encoder> {
        Encoder(String publicKey,String privateKey,String subject) throws java.security.GeneralSecurityException { super(publicKey,privateKey,subject); }
        nl.martijndwars.webpush.HttpRequest encode(Notification note) throws Exception { return prepareRequest(note,Encoding.AES128GCM); }
    }
    public boolean available() { return service!=null; }
    public String publicKey() { return available()?publicKey:""; }
    public static boolean allowedEndpoint(String endpoint) {
        try { var u=URI.create(endpoint);String h=u.getHost();return endpoint.length()<=2048 && "https".equals(u.getScheme()) && u.getUserInfo()==null && u.getFragment()==null
            && (u.getPort()==-1 || u.getPort()==443) && h!=null && (h.equals("fcm.googleapis.com") || h.equals("web.push.apple.com") || h.endsWith(".push.services.mozilla.com")); }
        catch(RuntimeException e) { return false; }
    }
    public static void validate(PushSubscription sub) {
        if(!allowedEndpoint(sub.endpoint))throw new IllegalArgumentException("This push service is not supported.");
        try { byte[] pub=Base64.getUrlDecoder().decode(sub.p256dh),auth=Base64.getUrlDecoder().decode(sub.auth);
            if(pub.length!=65 || pub[0]!=4 || auth.length!=16)throw new IllegalArgumentException();
            Security.addProvider(new BouncyCastleProvider());new Notification(sub.endpoint,sub.p256dh,sub.auth,new byte[0],60);
        } catch(Exception e) { throw new IllegalArgumentException("Invalid notification subscription."); }
    }
    public int send(PushSubscription sub,String title,String body,String tag) throws Exception {
        if(!available() || !allowedEndpoint(sub.endpoint))return 503;
        byte[] data=JsonMapper.builder().build().writeValueAsBytes(Map.of("title",title,"body",body,"tag",tag,"url","/"));
        var note=new Notification(sub.endpoint,sub.p256dh,sub.auth,data,60);
        var prepared=service.encode(note);
        var request=java.net.http.HttpRequest.newBuilder(URI.create(sub.endpoint)).timeout(Duration.ofSeconds(8)).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(prepared.getBody()));
        prepared.getHeaders().forEach(request::header);
        return client.send(request.build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
