package com.unisage.backend.controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.service.conversation.ConversationService;
import com.unisage.backend.service.guestsession.GuestSessionService;
import com.unisage.backend.service.systemconfig.SystemConfigResolver;
import com.unisage.backend.utils.CookieUtil;
import com.unisage.backend.utils.SecurityUtil;

import jakarta.servlet.http.HttpServletRequest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** UNISAGE-107 §4: identity comes from the JWT / guest cookie, never from the request. */
class ConversationControllerTest {

    private final ConversationService conversationService = mock(ConversationService.class);
    private final SecurityUtil securityUtil = mock(SecurityUtil.class);
    private final GuestSessionService guestSessionService = mock(GuestSessionService.class);
    private final CookieUtil cookieUtil = mock(CookieUtil.class);
    private final ConversationController controller = new ConversationController(
            conversationService, securityUtil, guestSessionService, cookieUtil, mock(SystemConfigResolver.class));

    @Test
    void getByUser_ignoresTheUserIdParam_andListsTheCallersOwn() {
        UUID me = UUID.randomUUID();
        when(securityUtil.getCurrentUserId()).thenReturn(me);
        when(conversationService.getByUser(me)).thenReturn(List.of());

        controller.getByUser(UUID.randomUUID().toString());
        controller.getByUser(null);

        verify(conversationService, org.mockito.Mockito.times(2)).getByUser(me);
    }

    @Test
    void softDelete_passesTheCaller() {
        UUID me = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(securityUtil.getCurrentUserId()).thenReturn(me);

        controller.softDelete(id);

        verify(conversationService).softDelete(id, me);
    }

    @Test
    void claim_resolvesTheGuestCookieReadOnly() {
        UUID me = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        HttpServletRequest request = mock(HttpServletRequest.class);
        GuestSession session = GuestSession.builder().id(UUID.randomUUID()).build();
        when(securityUtil.getCurrentUserId()).thenReturn(me);
        when(cookieUtil.extractGuestSessionTokenFromCookie(request)).thenReturn("cookie");
        when(guestSessionService.resolveReadOnly("cookie")).thenReturn(Optional.of(session));

        controller.claim(id, request);

        verify(conversationService).claim(id, me, session);
        verify(guestSessionService, never()).resolveOrCreate(any());
        verify(guestSessionService, never()).resolveAndTouch(any());
    }
}
