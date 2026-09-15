package com.atlaso.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import javax.crypto.spec.SecretKeySpec

@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${atlaso.auth.jwt-secret}") private val jwtSecret: String
) {

    @Bean
    fun jwtDecoder(): JwtDecoder {
        val key = SecretKeySpec(jwtSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        return NimbusJwtDecoder.withSecretKey(key).build()
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(HttpMethod.GET, "/health").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/google").permitAll()
                    // Razorpay webhook — no JWT; authenticated by the HMAC signature over the raw body.
                    .requestMatchers(HttpMethod.POST, "/api/payments/webhook").permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    // Guest trip flow — no auth required until claim
                    .requestMatchers(HttpMethod.POST, "/api/trips").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/trips/*").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/trips/*/photos").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/trips/*/photos/initiate").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/trips/*/photos/confirm").permitAll()
                    // Public image/file access
                    .requestMatchers(HttpMethod.GET, "/api/trips/*/photos/*/image").permitAll()
                    // Book cover for the book-ready email — capability-gated by the book UUID
                    .requestMatchers(HttpMethod.GET, "/api/books/*/cover").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/internal/file").permitAll()
                    .requestMatchers(HttpMethod.PUT, "/api/internal/upload").permitAll()
                    // Admin dashboard — JWT bypassed; guarded by AdminKeyInterceptor (X-Admin-Key)
                    .requestMatchers("/api/admin/**").permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt -> jwt.decoder(jwtDecoder()) }
            }
        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration()
        val origins = (System.getenv("ALLOWED_ORIGINS") ?: "http://localhost:3000")
            .split(",").map { it.trim() }
        config.allowedOrigins = origins
        config.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        config.allowedHeaders = listOf("Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With", "X-Admin-Key", "X-Guest-Token")
        config.allowCredentials = false
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/api/**", config)
        return source
    }
}
