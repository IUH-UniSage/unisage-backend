package com.unisage.backend.config;

import com.unisage.backend.entity.Role;
import com.unisage.backend.entity.User;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicAuthorizationManagerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private Authentication authentication;

    @Mock
    private RequestAuthorizationContext context;

    @Mock
    private HttpServletRequest request;

    @Test
    void whenTheAssignedRoleIsInactiveThenTheRequestIsDenied() {
        UUID userId = UUID.randomUUID();
        User user = org.mockito.Mockito.mock(User.class);
        Role role = org.mockito.Mockito.mock(Role.class);
        DynamicAuthorizationManager manager = new DynamicAuthorizationManager(userRepository);
        ReflectionTestUtils.setField(manager, "contextPath", "/api/v1");

        when(context.getRequest()).thenReturn(request);
        when(request.getRequestURI()).thenReturn("/api/v1/rbac/roles");
        when(request.getMethod()).thenReturn("GET");
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(
                UserPrincipal.builder().userId(userId).build());
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));
        when(user.getIsActive()).thenReturn(true);
        when(user.getRole()).thenReturn(role);
        when(role.getIsActive()).thenReturn(false);

        AuthorizationDecision decision = manager.check(() -> authentication, context);

        assertFalse(decision.isGranted());
    }
}
