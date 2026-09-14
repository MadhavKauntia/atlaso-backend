package com.atlaso.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val adminKeyInterceptor: AdminKeyInterceptor,
    private val rateLimitInterceptor: RateLimitInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(adminKeyInterceptor).addPathPatterns("/api/admin/**")
        // Rate limiter self-selects the cost-sensitive routes; scope to /api/** to skip static/health.
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**")
    }
}
