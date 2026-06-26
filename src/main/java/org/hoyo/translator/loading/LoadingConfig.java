package org.hoyo.translator.loading;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
@EnableScheduling
public class LoadingConfig {

    /**
     * Single-threaded executor for (re)loading data into Redis. Sequential Redis
     * writes don't benefit from extra threads, and we don't want loading to compete
     * with request-handling threads.
     */
    @Bean(name = "dataLoaderExecutor")
    public TaskExecutor dataLoaderExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("data-loader-");
        executor.initialize();
        return executor;
    }
}
