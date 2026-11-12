package com.wenjie.studio.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {
    @Bean(name = "aiScheduler")
    ThreadPoolTaskScheduler aiScheduler() {
        // 仅注册一个固定轮询任务；业务队列在数据库中，网络等待不占用其他域的调度器。
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ai-worker-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Bean(name = "taskScheduler")
    @ConditionalOnProperty(name = "studio.material.enabled", havingValue = "false", matchIfMissing = true)
    ThreadPoolTaskScheduler workspaceScheduler() {
        // 附件关闭时仍为搜索提供独立默认调度线程，不能回落到 AI 网络线程。
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("workspace-schedule-");
        return scheduler;
    }
}
