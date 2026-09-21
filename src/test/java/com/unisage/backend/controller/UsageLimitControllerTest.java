package com.unisage.backend.controller;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.UsageLimitResponse;
import com.unisage.backend.dto.response.UsageWindowResponse;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.enums.UsageWindowStatus;
import com.unisage.backend.predefined.PredefinedPublicPaths;
import com.unisage.backend.service.guestsession.GuestSessionService;
import com.unisage.backend.service.usagelimit.UsageLimitService;
import com.unisage.backend.utils.CookieUtil;
import com.unisage.backend.utils.SecurityUtil;

import jakarta.servlet.http.HttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageLimitControllerTest {

    private final UsageLimitResponse usage = new UsageLimitResponse(
            UsageWindowResponse.builder().status(UsageWindowStatus.IDLE).remainingPercent(100).build(),
            UsageWindowResponse.builder().status(UsageWindowStatus.IDLE).remainingPercent(100).build());

    private UsageLimitService usageLimitService;
    private SecurityUtil securityUtil;
    private GuestSessionService guestSessionService;
    private CookieUtil cookieUtil;
    private UsageLimitController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        usageLimitService = mock(UsageLimitService.class);
        securityUtil = mock(SecurityUtil.class);
        guestSessionService = mock(GuestSessionService.class);
        cookieUtil = mock(CookieUtil.class);
        request = mock(HttpServletRequest.class);
        controller = new UsageLimitController(usageLimitService, securityUtil, guestSessionService, cookieUtil);
        when(usageLimitService.getUsage(any(), any())).thenReturn(usage);
    }

    @Test
    void signedInUser_readsOwnUsageFromJwt_andIgnoresGuestCookie() {
        UUID userId = UUID.randomUUID();
        when(securityUtil.getCurrentUserIdOrNull()).thenReturn(userId);

        ResponseEntity<ApiResponse<UsageLimitResponse>> response = controller.getMine(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data()).isEqualTo(usage);
        verify(usageLimitService).getUsage(userId, null);
        verify(guestSessionService, never()).resolveReadOnly(any());
    }

    @Test
    void guestWithValidCookie_readsUsageOfTheirSession() {
        GuestSession session = GuestSession.builder().id(UUID.randomUUID()).build();
        when(securityUtil.getCurrentUserIdOrNull()).thenReturn(null);
        when(cookieUtil.extractGuestSessionTokenFromCookie(request)).thenReturn("token");
        when(guestSessionService.resolveReadOnly("token")).thenReturn(Optional.of(session));

        controller.getMine(request);

        verify(usageLimitService).getUsage(null, session);
    }

    @Test
    void guestWithoutValidSession_getsDefaultPlanUsage_withoutMintingASession() {
        when(securityUtil.getCurrentUserIdOrNull()).thenReturn(null);
        when(cookieUtil.extractGuestSessionTokenFromCookie(request)).thenReturn(null);
        when(guestSessionService.resolveReadOnly(any())).thenReturn(Optional.empty());

        controller.getMine(request);

        verify(usageLimitService).getUsage(null, null);
        verify(guestSessionService, never()).resolveOrCreate(any());
    }

    @Test
    void usageLimitsMe_isReachableByGuestsAndUsers() {
        assertThat(PredefinedPublicPaths.PUBLIC_PATHS)
                .contains(new PredefinedPublicPaths.PublicPath("GET", "/usage-limits/me"));
    }
}
