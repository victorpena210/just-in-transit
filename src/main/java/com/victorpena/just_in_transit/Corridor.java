package com.victorpena.just_in_transit;

/** Stop order, not the address label, establishes the supported direction. */
public record Corridor(String key,long savedId,String title,String firstRoute,String secondRoute,
                       String transferOff,String transferOn,String defaultOrigin,String defaultDestination) {
    public static Corridor of(String key) {
        return "home".equals(key)?new Corridor("home",2L,"Going home","93","24","76779","76773","75229","81793"):
            new Corridor("work",1L,"Going to work","24","93","76779","76773","81799","83463");
    }
    public String routes() { return firstRoute+" → "+secondRoute; }
}
