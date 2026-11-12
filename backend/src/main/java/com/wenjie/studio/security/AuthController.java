package com.wenjie.studio.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public final class AuthController {
    private final OwnerProperties owner;

    public AuthController(OwnerProperties owner) {
        this.owner = owner;
    }

    @GetMapping("/session")
    public SessionState session(Authentication authentication) {
        boolean authenticated = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && owner.username().equals(authentication.getName());
        return authenticated ? new SessionState(true, owner.username(), owner.ownerId())
                : new SessionState(false, null, null);
    }

    @GetMapping("/csrf")
    public CsrfState csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return new CsrfState(token.getHeaderName(), token.getToken());
    }

    public record SessionState(boolean authenticated, String username, String ownerId) { }
    public record CsrfState(String headerName, String token) { }
}
