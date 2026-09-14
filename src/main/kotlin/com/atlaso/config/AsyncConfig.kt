package com.atlaso.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

/**
 * Enables @Async and provides a bounded pool for background book generation, so concurrent
 * generations are capped rather than spawning unbounded threads.
 */
@Configuration
@EnableAsync
class AsyncConfig {

    @Bean("bookGenerationExecutor")
    fun bookGenerationExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 2
        executor.maxPoolSize = 4
        executor.queueCapacity = 50
        executor.setThreadNamePrefix("book-gen-")
        executor.initialize()
        return executor
    }
}
