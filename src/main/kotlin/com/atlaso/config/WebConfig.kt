package com.atlaso.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        val allowed = System.getenv("ALLOWED_ORIGINS")
            ?.split(",")?.map { it.trim() }?.toTypedArray()
            ?: arrayOf("http://localhost:3000")

        registry.addMapping("/api/**")
            .allowedOrigins(*allowed)
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
    }
}
