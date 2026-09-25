package com.unisage.backend.config;

import com.unisage.backend.security.GatewayHeaderFilter;
import com.unisage.backend.security.InternalCallerCidrFilter;
import com.unisage.backend.security.InternalResponseHeadersFilter;
import com.unisage.backend.security.InternalSecretFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final GatewayHeaderFilter gatewayHeaderFilter;
    private final InternalSecretFilter internalSecretFilter;
    private final InternalCallerCidrFilter internalCallerCidrFilter;
    private final InternalResponseHeadersFilter internalResponseHeadersFilter;
    private final DynamicAuthorizationManager dynamicAuthorizationManager;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.disable())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().access(dynamicAuthorizationManager)
            )
            .addFilterBefore(gatewayHeaderFilter, UsernamePasswordAuthenticationFilter.class)
            // Order: no-store headers, secret check, CIDR check, then gateway JWT.
            .addFilterBefore(internalCallerCidrFilter, GatewayHeaderFilter.class)
            .addFilterBefore(internalSecretFilter, InternalCallerCidrFilter.class)
            .addFilterBefore(internalResponseHeadersFilter, InternalSecretFilter.class);

        return http.build();
    }
}