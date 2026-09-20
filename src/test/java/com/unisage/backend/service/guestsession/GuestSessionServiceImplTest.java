package com.unisage.backend.service.guestsession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.GuestSessionRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.UsageLimitRepository;
import com.unisage.backend.service.guestsession.GuestSessionService.GuestSessionResolution;
import com.unisage.backend.service.systemconfig.SystemConfigResolver;
import com.unisage.backend.utils.TokenHashUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuestSessionServiceImplTest {

    private GuestSessionRepository guestSessionRepository;
    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private UsageLimitRepository usageLimitRepository;
    private TokenHashUtil tokenHashUtil;
    private GuestSessionServiceImpl guestSessionService;

    @BeforeEach
    void setUp() {
        guestSessionRepository = mock(GuestSessionRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(MessageRepository.class);
        usageLimitRepository = mock(UsageLimitRepository.class);
        tokenHashUtil = mock(TokenHashUtil.class);
        SystemConfigResolver configResolver = mock(SystemConfigResolver.class);
        when(configResolver.getInt(any(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        guestSessionService = new GuestSessionServiceImpl(
                guestSessionRepository, conversationRepository, messageRepository, usageLimitRepository,
                tokenHashUtil, configResolver);

        when(guestSessionRepository.save(any(GuestSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void resolveOrCreate_noCookieToken_mintsNewSession() {
        when(tokenHashUtil.generateToken()).thenReturn("new-raw-token");
        when(tokenHashUtil.sha256Hex("new-raw-token")).thenReturn("new-hash");

        GuestSessionResolution resolution = guestSessionService.resolveOrCreate(null);

        assertThat(resolution.rawToken()).isEqualTo("new-raw-token");
        assertThat(resolution.session().getTokenHash()).isEqualTo("new-hash");
        verify(guestSessionRepository, never()).findByTokenHashAndExpiresAtAfter(any(), any());
    }

    @Test
    void resolveOrCreate_validExistingToken_reusesSessionAndSameRawToken() {
        GuestSession existing = GuestSession.builder().tokenHash("existing-hash").build();
        when(tokenHashUtil.sha256Hex("existing-token")).thenReturn("existing-hash");
        when(guestSessionRepository.findByTokenHashAndExpiresAtAfter(eq("existing-hash"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(existing));

        GuestSessionResolution resolution = guestSessionService.resolveOrCreate("existing-token");

        assertThat(resolution.rawToken()).isEqualTo("existing-token");
        assertThat(resolution.session()).isSameAs(existing);
        verify(tokenHashUtil, never()).generateToken();
    }

    @Test
    void resolveOrCreate_invalidOrExpiredToken_mintsNewSessionInstead() {
        when(tokenHashUtil.sha256Hex("stale-token")).thenReturn("stale-hash");
        when(guestSessionRepository.findByTokenHashAndExpiresAtAfter(eq("stale-hash"), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(tokenHashUtil.generateToken()).thenReturn("fresh-token");
        when(tokenHashUtil.sha256Hex("fresh-token")).thenReturn("fresh-hash");

        GuestSessionResolution resolution = guestSessionService.resolveOrCreate("stale-token");

        assertThat(resolution.rawToken()).isEqualTo("fresh-token");
        assertThat(resolution.session().getTokenHash()).isEqualTo("fresh-hash");
    }

    @Test
    void resolveAndTouch_validToken_returnsSessionAndRefreshesExpiry() {
        GuestSession existing = GuestSession.builder().tokenHash("hash").expiresAt(LocalDateTime.now()).build();
        when(tokenHashUtil.sha256Hex("token")).thenReturn("hash");
        when(guestSessionRepository.findByTokenHashAndExpiresAtAfter(eq("hash"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(existing));

        LocalDateTime before = existing.getExpiresAt();
        Optional<GuestSessionResolution> result = guestSessionService.resolveAndTouch("token");

        assertThat(result).isPresent();
        assertThat(existing.getExpiresAt()).isAfter(before);
    }

    @Test
    void resolveAndTouch_missingToken_returnsEmptyAndNeverCreates() {
        Optional<GuestSessionResolution> result = guestSessionService.resolveAndTouch(null);

        assertThat(result).isEmpty();
        verify(guestSessionRepository, never()).save(any());
    }

    @Test
    void resolveAndTouch_invalidToken_returnsEmptyAndNeverCreates() {
        when(tokenHashUtil.sha256Hex("bad-token")).thenReturn("bad-hash");
        when(guestSessionRepository.findByTokenHashAndExpiresAtAfter(eq("bad-hash"), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        Optional<GuestSessionResolution> result = guestSessionService.resolveAndTouch("bad-token");

        assertThat(result).isEmpty();
        verify(guestSessionRepository, never()).save(any());
    }

    @Test
    void resolveReadOnly_validToken_returnsSessionWithoutRefreshingOrCreating() {
        GuestSession existing = GuestSession.builder().tokenHash("hash").expiresAt(LocalDateTime.now()).build();
        when(tokenHashUtil.sha256Hex("token")).thenReturn("hash");
        when(guestSessionRepository.findByTokenHashAndExpiresAtAfter(eq("hash"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(existing));

        LocalDateTime before = existing.getExpiresAt();
        Optional<GuestSession> result = guestSessionService.resolveReadOnly("token");

        assertThat(result).contains(existing);
        assertThat(existing.getExpiresAt()).isEqualTo(before); // not refreshed
        verify(guestSessionRepository, never()).save(any());
    }

    @Test
    void resolveReadOnly_missingOrInvalidToken_returnsEmptyAndNeverCreates() {
        assertThat(guestSessionService.resolveReadOnly(null)).isEmpty();
        assertThat(guestSessionService.resolveReadOnly("")).isEmpty();
        verify(guestSessionRepository, never()).save(any());
    }

    @Test
    void purgeExpiredBatch_noExpiredSessions_deletesNothing() {
        when(guestSessionRepository.findExpiredIds(any(), any())).thenReturn(List.of());

        int purged = guestSessionService.purgeExpiredBatch(500);

        assertThat(purged).isZero();
        verify(messageRepository, never()).deleteByConversationGuestSessionIdIn(anyCollection());
        verify(usageLimitRepository, never()).deleteByGuestSessionIdIn(anyCollection());
        verify(conversationRepository, never()).deleteByGuestSessionIdIn(anyCollection());
        verify(guestSessionRepository, never()).deleteAllByIdInBatch(anyCollection());
    }

    @Test
    void purgeExpiredBatch_expiredSessions_deletesInFkSafeOrder() {
        List<UUID> expiredIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(guestSessionRepository.findExpiredIds(any(), any())).thenReturn(expiredIds);

        int purged = guestSessionService.purgeExpiredBatch(500);

        assertThat(purged).isEqualTo(2);
        verify(messageRepository, times(1)).deleteByConversationGuestSessionIdIn(expiredIds);
        verify(usageLimitRepository, times(1)).deleteByGuestSessionIdIn(expiredIds);
        verify(conversationRepository, times(1)).deleteByGuestSessionIdIn(expiredIds);
        verify(guestSessionRepository, times(1)).deleteAllByIdInBatch(expiredIds);
    }
}
