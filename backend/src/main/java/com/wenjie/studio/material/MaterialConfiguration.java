package com.wenjie.studio.material;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(MaterialProperties.class)
public class MaterialConfiguration {
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "studio.material.enabled", havingValue = "true")
    static class Scheduling {
        @Bean(name = "taskScheduler")
        ThreadPoolTaskScheduler workspaceScheduler() {
            // 默认定时任务使用另一条线程，资料子进程的等待不会占用搜索投影的调度器。
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("workspace-schedule-");
            return scheduler;
        }

        @Bean(name = "materialScheduler")
        ThreadPoolTaskScheduler materialScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("material-parser-");
            scheduler.setWaitForTasksToCompleteOnShutdown(false);
            return scheduler;
        }
    }
}
