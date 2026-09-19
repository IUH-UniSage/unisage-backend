package com.unisage.backend.config;

import com.unisage.backend.entity.User;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.AntPathMatcher;

import com.unisage.backend.predefined.PredefinedPublicPaths.PublicPath;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.unisage.backend.predefined.PredefinedPublicPaths.AUTHENTICATED_ONLY_PATHS;
import static com.unisage.backend.predefined.PredefinedPublicPaths.PUBLIC_PATHS;

@Component
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class DynamicAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    UserRepository userRepository;
    AntPathMatcher pathMatcher = new AntPathMatcher();

    @NonFinal
    @Value("${server.servlet.context-path:}")
    String contextPath;

    @Override
    @Transactional(readOnly = true)
    public AuthorizationDecision check(Supplier<Authentication> authSupplier,
                                       RequestAuthorizationContext context) {

        HttpServletRequest request = context.getRequest();
        String fullPath = request.getRequestURI();
        final String normalizedPath = fullPath.replace(contextPath, "");
        String httpMethod = request.getMethod();

        log.debug("DynamicAuthZ → {} {}", httpMethod, normalizedPath);

        try {
            if (isPublicPath(normalizedPath, httpMethod)) {
                log.debug("Public path — granted: {}", normalizedPath);
                return new AuthorizationDecision(true);
            }

            Authentication auth = authSupplier.get();
            if (auth == null || !auth.isAuthenticated()
                    || "anonymousUser".equals(auth.getPrincipal())) {
                log.debug("Unauthenticated — denied: {}", normalizedPath);
                return new AuthorizationDecision(false);
            }

            UUID userId;
            try {
                UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
                userId = principal.getUserId();
            } catch (Exception e) {
                log.warn("Cannot resolve UserPrincipal: {}", e.getMessage());
                return new AuthorizationDecision(false);
            }

            User user = userRepository.findByIdWithPermissions(userId).orElse(null);

            if (user == null) {
                log.warn("User not found for id: {}", userId);
                return new AuthorizationDecision(false);
            }

            if (!user.getIsActive()) {
                log.warn("User {} is inactive — denied", userId);
                return new AuthorizationDecision(false);
            }

            if (user.getRole() == null) {
                log.warn("User {} has no role assigned — denied", userId);
                return new AuthorizationDecision(false);
            }

            if (matches(AUTHENTICATED_ONLY_PATHS, normalizedPath, httpMethod)) {
                log.debug("Authenticated-only path — granted: {}", normalizedPath);
                return new AuthorizationDecision(true);
            }

            boolean granted = user.getRole().getRolePermissions().stream()
                    .map(rp -> rp.getPermission())
                    .filter(p -> p != null && Boolean.TRUE.equals(p.getIsActive()))
                    .anyMatch(permission -> {
                        boolean pathMatch = pathMatcher.match(permission.getPath(), normalizedPath);
                        boolean methodMatch = permission.getMethod().name().equalsIgnoreCase(httpMethod)
                                || "ALL".equalsIgnoreCase(permission.getMethod().name());
                        if (pathMatch && methodMatch) {
                            log.debug("Permission hit: [{}] {} → {}",
                                    permission.getMethod(), permission.getPath(), permission.getName());
                        }
                        return pathMatch && methodMatch;
                    });

            if (!granted) {
                log.warn("User {} denied for [{} {}]", userId, httpMethod, normalizedPath);
            }
            return new AuthorizationDecision(granted);

        } catch (Exception e) {
            log.error("Error during dynamic authorization check", e);
            return new AuthorizationDecision(false);
        }
    }

    private boolean isPublicPath(String path, String method) {
        return matches(PUBLIC_PATHS, path, method);
    }

    private boolean matches(List<PublicPath> paths, String path, String method) {
        return paths.stream().anyMatch(p ->
                ("*".equals(p.method()) || p.method().equalsIgnoreCase(method))
                        && pathMatcher.match(p.pattern(), path));
    }
}
