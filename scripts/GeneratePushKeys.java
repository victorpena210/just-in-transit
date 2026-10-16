import java.security.*;import java.security.spec.*;import java.security.interfaces.*;import java.util.*;
/** Run: java scripts/GeneratePushKeys.java. Store the private key in deployment secrets. */
class GeneratePushKeys {
    static byte[] fixed(java.math.BigInteger value){byte[] n=value.toByteArray(),out=new byte[32];System.arraycopy(n,Math.max(0,n.length-32),out,Math.max(0,32-n.length),Math.min(32,n.length));return out;}
    public static void main(String[] args)throws Exception {
        var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));var pair=generator.generateKeyPair();var pub=((ECPublicKey)pair.getPublic()).getW();byte[] raw=new byte[65];raw[0]=4;System.arraycopy(fixed(pub.getAffineX()),0,raw,1,32);System.arraycopy(fixed(pub.getAffineY()),0,raw,33,32);
        System.out.println("JIT_PUSH_PUBLIC_KEY="+Base64.getUrlEncoder().withoutPadding().encodeToString(raw));System.out.println("JIT_PUSH_PRIVATE_KEY="+Base64.getUrlEncoder().withoutPadding().encodeToString(fixed(((ECPrivateKey)pair.getPrivate()).getS())));
    }
}
