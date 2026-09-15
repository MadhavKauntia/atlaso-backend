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

    /** Small pool for post-commit order side effects (receipt render + confirmation email), kept
     *  off the request thread so the Razorpay webhook can respond within its 5s budget. */
    @Bean("orderNotificationExecutor")
    fun orderNotificationExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 1
        executor.maxPoolSize = 3
        executor.queueCapacity = 100
        executor.setThreadNamePrefix("order-notify-")
        executor.initialize()
        return executor
    }
}
