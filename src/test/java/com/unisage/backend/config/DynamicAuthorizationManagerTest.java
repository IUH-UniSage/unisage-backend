package com.unisage.backend.config;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.test.util.ReflectionTestUtils;

import com.unisage.backend.entity.Role;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.UserStatus;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.security.UserPrincipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicAuthorizationManagerTest {

    private final UUID userId = UUID.randomUUID();
    private UserRepository userRepository;
    private DynamicAuthorizationManager manager;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        manager = new DynamicAuthorizationManager(userRepository);
        ReflectionTestUtils.setField(manager, "contextPath", "/api/v1");
    }

    private boolean granted(Authentication auth, String method, String path) {
        var request = new MockHttpServletRequest(method, "/api/v1" + path);
        return manager.check(() -> auth, new RequestAuthorizationContext(request)).isGranted();
    }

    private Authentication authenticated() {
        var principal = UserPrincipal.builder().userId(userId).build();
        return new UsernamePasswordAuthenticationToken(principal, null, AuthorityUtils.NO_AUTHORITIES);
    }

    private User user(UserStatus status, Role role) {
        return User.builder().id(userId).status(status).role(role).build();
    }

    private Role roleWithoutPermissions() {
        Role role = new Role();
        role.setRolePermissions(new ArrayList<>());
        return role;
    }

    @Test
    void usersMe_anonymousIsDenied() {
        var anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertThat(granted(anonymous, "GET", "/users/me")).isFalse();
    }

    @Test
    void usersMe_grantedToActiveUserWithoutAnyPermissionRow() {
        when(userRepository.findByIdWithPermissions(userId))
                .thenReturn(Optional.of(user(UserStatus.ACTIVE, roleWithoutPermissions())));

        assertThat(granted(authenticated(), "GET", "/users/me")).isTrue();
    }

    @Test
    void usersMe_deniedToInactiveUser() {
        when(userRepository.findByIdWithPermissions(userId))
                .thenReturn(Optional.of(user(UserStatus.INACTIVE, roleWithoutPermissions())));

        assertThat(granted(authenticated(), "GET", "/users/me")).isFalse();
    }

    @Test
    void usersMe_deniedToUserWithoutRole() {
        when(userRepository.findByIdWithPermissions(userId))
                .thenReturn(Optional.of(user(UserStatus.ACTIVE, null)));

        assertThat(granted(authenticated(), "GET", "/users/me")).isFalse();
    }

    @Test
    void otherUserEndpoints_stillRequireAPermissionRow() {
        when(userRepository.findByIdWithPermissions(userId))
                .thenReturn(Optional.of(user(UserStatus.ACTIVE, roleWithoutPermissions())));

        assertThat(granted(authenticated(), "GET", "/users/" + UUID.randomUUID())).isFalse();
        assertThat(granted(authenticated(), "PUT", "/users/me")).isFalse();
    }
}
