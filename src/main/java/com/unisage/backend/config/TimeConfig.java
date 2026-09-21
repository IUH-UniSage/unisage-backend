package com.unisage.backend.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** UTC clock; injected so time-dependent services (usage windows) are testable. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
