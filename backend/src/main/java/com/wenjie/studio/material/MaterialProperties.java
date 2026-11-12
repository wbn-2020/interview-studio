package com.wenjie.studio.material;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("studio.material")
public record MaterialProperties(@DefaultValue("false") boolean enabled,
                                 @DefaultValue("") String directory,
                                 @DefaultValue("false") boolean parserEnabled,
                                 @DefaultValue("1073741824") long quotaBytes) {
    public MaterialProperties {
        if (quotaBytes < 20L * 1024 * 1024 || quotaBytes > 9_007_199_254_740_991L)
            throw new IllegalStateException("资料容量必须至少为 20 MiB，且不超过安全整数范围。");
    }
}
