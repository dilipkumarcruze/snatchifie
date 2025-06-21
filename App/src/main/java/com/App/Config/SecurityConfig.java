// Comment out or delete this file to disable Spring Security
package com.App.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
// import org.springframework.security.config.annotation.web.builders.HttpSecurity;
// import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    // @Bean
    // public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    //     http
    //         .csrf(csrf -> csrf.disable()) // Disable CSRF
    //         .authorizeHttpRequests(auth -> auth
    //             .anyRequest().authenticated() // Remove this line to allow all requests
    //         );
    //     return http.build();
    // }
}
