package com.victorpena.just_in_transit;
import static org.junit.jupiter.api.Assertions.*;
import java.security.*;import java.security.interfaces.*;import java.security.spec.*;import java.util.*;import java.nio.*;import java.nio.charset.StandardCharsets;
import javax.crypto.*;import javax.crypto.spec.*;import org.junit.jupiter.api.Test;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import nl.martijndwars.webpush.Notification;

/** Independent JCA decryption checks the library's RFC8291 wire format, rather than mocking encryption. */
class PushEncodingTests {
    static byte[] fixed(java.math.BigInteger n){byte[] b=n.toByteArray(),o=new byte[32];System.arraycopy(b,Math.max(0,b.length-32),o,Math.max(0,32-b.length),Math.min(32,b.length));return o;}
    static byte[] raw(PublicKey key){var q=((ECPublicKey)key).getW();return cat(new byte[]{4},fixed(q.getAffineX()),fixed(q.getAffineY()));}
    static byte[] cat(byte[]...arrays){var out=new java.io.ByteArrayOutputStream();for(var a:arrays)out.writeBytes(a);return out.toByteArray();}
    static String b64(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    static byte[] hmac(byte[] key,byte[] value)throws Exception{var m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal(value);}
    static byte[] expand(byte[] key,String info,int length)throws Exception{return Arrays.copyOf(hmac(key,cat(info.getBytes(StandardCharsets.UTF_8),new byte[]{0,1})),length);}
    @Test void encryptedPayloadAndVapidSignatureWorkWithJava17()throws Exception{
        Security.addProvider(new BouncyCastleProvider());var gen=KeyPairGenerator.getInstance("EC");gen.initialize(new ECGenParameterSpec("secp256r1"));var signing=gen.generateKeyPair();var client=gen.generateKeyPair();byte[] auth=new byte[16];new SecureRandom().nextBytes(auth);
        var encoder=new PushTransport.Encoder(b64(raw(signing.getPublic())),b64(fixed(((ECPrivateKey)signing.getPrivate()).getS())),"mailto:test@example.com");
        byte[] plaintext="{\"body\":\"Leave in five minutes\"}".getBytes(StandardCharsets.UTF_8);
        var message=encoder.encode(new Notification("https://web.push.apple.com/test",b64(raw(client.getPublic())),b64(auth),plaintext,60));
        assertEquals("aes128gcm",message.getHeaders().get("Content-Encoding"));assertEquals("60",message.getHeaders().get("TTL"));
        byte[] body=message.getBody();var buffer=ByteBuffer.wrap(body);byte[] salt=new byte[16];buffer.get(salt);assertTrue(buffer.getInt()>plaintext.length+17);int keyLength=Byte.toUnsignedInt(buffer.get());assertEquals(65,keyLength);byte[] server=new byte[keyLength];buffer.get(server);byte[] encrypted=new byte[buffer.remaining()];buffer.get(encrypted);
        var params=AlgorithmParameters.getInstance("EC");params.init(new ECGenParameterSpec("secp256r1"));var spec=params.getParameterSpec(ECParameterSpec.class);
        var point=new ECPoint(new java.math.BigInteger(1,Arrays.copyOfRange(server,1,33)),new java.math.BigInteger(1,Arrays.copyOfRange(server,33,65)));
        var ephemeral=KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point,spec));var agreement=KeyAgreement.getInstance("ECDH");agreement.init(client.getPrivate());agreement.doPhase(ephemeral,true);
        byte[] prkKey=hmac(auth,agreement.generateSecret());byte[] ikm=hmac(prkKey,cat("WebPush: info".getBytes(StandardCharsets.UTF_8),new byte[]{0},raw(client.getPublic()),server,new byte[]{1}));byte[] prk=hmac(salt,ikm);
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(expand(prk,"Content-Encoding: aes128gcm",16),"AES"),new GCMParameterSpec(128,expand(prk,"Content-Encoding: nonce",12)));byte[] decoded=cipher.doFinal(encrypted);
        assertEquals(2,decoded[decoded.length-1]);assertArrayEquals(plaintext,Arrays.copyOf(decoded,decoded.length-1));
        String authorization=message.getHeaders().get("Authorization");assertTrue(authorization.startsWith("vapid t="));String jwt=authorization.substring(8).split(",")[0];String[] parts=jwt.split("\\.");
        var verify=Signature.getInstance("SHA256withECDSAinP1363Format");verify.initVerify(signing.getPublic());verify.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));assertTrue(verify.verify(Base64.getUrlDecoder().decode(parts[2])));
    }
}
