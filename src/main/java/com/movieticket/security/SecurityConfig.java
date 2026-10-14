package com.movieticket.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movieticket.common.response.ApiError;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService, ObjectMapper mapper)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // stateless token API, no cookies/sessions
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/users/register", "/api/users/login").permitAll()
                        // browsing is public
                        .requestMatchers(HttpMethod.GET, "/api/movies/**", "/api/theatres/**", "/api/shows/**").permitAll()
                        // catalogue management is ADMIN only
                        .requestMatchers(HttpMethod.POST, "/api/movies/**", "/api/theatres/**", "/api/shows/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/movies/**", "/api/theatres/**", "/api/shows/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/movies/**", "/api/theatres/**", "/api/shows/**").hasRole("ADMIN")
                        // static website (HTML/CSS/JS served from src/main/resources/static); all data still comes from /api/**
                        .requestMatchers(HttpMethod.GET, "/", "/*.html", "/css/**", "/js/**", "/assets/**", "/favicon.ico").permitAll()
                        // admin-only reporting endpoints
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint(mapper))
                        .accessDeniedHandler(deniedHandler(mapper)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private AuthenticationEntryPoint entryPoint(ObjectMapper mapper) {
        return (request, response, ex) -> {
            ApiError body = ApiError.of(HttpStatus.UNAUTHORIZED.value(), "Unauthorized", "UNAUTHENTICATED",
                    "A valid Bearer token is required", request.getRequestURI());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), body);
        };
    }

    private AccessDeniedHandler deniedHandler(ObjectMapper mapper) {
        return (request, response, ex) -> {
            ApiError body = ApiError.of(HttpStatus.FORBIDDEN.value(), "Forbidden", "ACCESS_DENIED",
                    "You do not have permission to perform this action", request.getRequestURI());
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), body);
        };
    }
}
