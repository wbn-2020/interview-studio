package com.wenjie.studio.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfiguration {
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "studio.search.enabled", havingValue = "true")
    static class Scheduling { }
}
