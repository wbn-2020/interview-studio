package com.wenjie.studio.dataarchive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("studio.dataarchive")
public record DataArchiveProperties(@DefaultValue("false") boolean enabled,
                                    @DefaultValue("") String directory,
                                    @DefaultValue("1073741824") long maximumBytes,
                                    @DefaultValue("1000000") long maximumRows,
                                    @DefaultValue("24") int expiryHours) {
    public DataArchiveProperties {
        if (maximumBytes < 20_971_520 || maximumBytes > 4_294_967_296L
                || maximumRows < 1 || maximumRows > 2_000_000 || expiryHours < 1 || expiryHours > 168)
            throw new IllegalStateException("完整导出的容量、记录数或有效期配置无效");
    }
}
