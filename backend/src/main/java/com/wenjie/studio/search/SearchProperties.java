package com.wenjie.studio.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("studio.search")
public record SearchProperties(boolean enabled, String directory, long pollDelayMs) { }
