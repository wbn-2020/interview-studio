package com.wenjie.studio.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;
import java.util.regex.Pattern;

@ConfigurationProperties("studio.security")
public record OwnerProperties(String ownerId, String username, String passwordHash) {
    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$(1[0-4])\\$[./A-Za-z0-9]{53}$");

    public void validate() {
        boolean validId = false;
        try {
            validId = ownerId != null && UUID.fromString(ownerId).toString().equals(ownerId);
        } catch (IllegalArgumentException ignored) {
            validId = false;
        }
        boolean validName = username != null && !username.isBlank() && username.length() <= 64
                && username.equals(username.strip());
        if (!validId || !validName || passwordHash == null || !BCRYPT.matcher(passwordHash).matches()) {
            // 绑定校验可能打印 rejectedValue；身份配置只抛固定消息，避免密码哈希进入日志。
            throw new IllegalStateException("管理员身份配置无效；请检查 owner UUID、登录名及 BCrypt 哈希（成本10–14）。");
        }
    }

    @Override
    public String toString() {
        return "OwnerProperties[服务器身份配置已隐藏]";
    }
}
