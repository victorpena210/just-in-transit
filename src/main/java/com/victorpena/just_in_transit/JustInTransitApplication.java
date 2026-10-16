package com.victorpena.just_in_transit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class JustInTransitApplication {
    @org.springframework.context.annotation.Bean
    java.time.Clock clock() { return java.time.Clock.systemUTC(); }

	public static void main(String[] args) {
		SpringApplication.run(JustInTransitApplication.class, args);
	}

}
