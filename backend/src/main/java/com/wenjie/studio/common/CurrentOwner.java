package com.wenjie.studio.common;

import com.wenjie.studio.security.OwnerProperties;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public final class CurrentOwner {
    private final OwnerProperties properties;

    public CurrentOwner(OwnerProperties properties) {
        this.properties = properties;
    }

    public String id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !properties.username().equals(authentication.getName())) {
            throw new ApiException(401, "AUTHENTICATION_REQUIRED", "请先登录后继续操作。");
        }
        return properties.ownerId();
    }
}
