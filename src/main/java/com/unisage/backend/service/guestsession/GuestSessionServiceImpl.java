package com.unisage.backend.service.guestsession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.GuestSessionRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.UsageLimitRepository;
import com.unisage.backend.utils.TokenHashUtil;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GuestSessionServiceImpl implements GuestSessionService {

    private final GuestSessionRepository guestSessionRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UsageLimitRepository usageLimitRepository;
    private final TokenHashUtil tokenHashUtil;

    @Value("${app.guest-session.ttl-days:30}")
    private int ttlDays;

    @Override
    @Transactional
    public GuestSessionResolution resolveOrCreate(String cookieTokenOrNull) {
        Optional<GuestSession> existing = lookupValid(cookieTokenOrNull);
        if (existing.isPresent()) {
            GuestSession session = existing.get();
            session.setExpiresAt(newExpiry());
            guestSessionRepository.save(session);
            return new GuestSessionResolution(session, cookieTokenOrNull);
        }

        String rawToken = tokenHashUtil.generateToken();
        GuestSession session = GuestSession.builder()
                .tokenHash(tokenHashUtil.sha256Hex(rawToken))
                .expiresAt(newExpiry())
                .build();
        session = guestSessionRepository.save(session);
        return new GuestSessionResolution(session, rawToken);
    }

    @Override
    @Transactional
    public Optional<GuestSessionResolution> resolveAndTouch(String cookieTokenOrNull) {
        return lookupValid(cookieTokenOrNull).map(session -> {
            session.setExpiresAt(newExpiry());
            guestSessionRepository.save(session);
            return new GuestSessionResolution(session, cookieTokenOrNull);
        });
    }

    @Override
    public Optional<GuestSession> resolveReadOnly(String cookieTokenOrNull) {
        return lookupValid(cookieTokenOrNull);
    }

    @Override
    @Transactional
    public int purgeExpiredBatch(int batchSize) {
        List<UUID> expiredIds = guestSessionRepository.findExpiredIds(
                LocalDateTime.now(), PageRequest.of(0, batchSize));
        if (expiredIds.isEmpty()) {
            return 0;
        }

        messageRepository.deleteByConversationGuestSessionIdIn(expiredIds);
        usageLimitRepository.deleteByGuestSessionIdIn(expiredIds);
        conversationRepository.deleteByGuestSessionIdIn(expiredIds);
        guestSessionRepository.deleteAllByIdInBatch(expiredIds);

        return expiredIds.size();
    }

    private Optional<GuestSession> lookupValid(String cookieTokenOrNull) {
        if (cookieTokenOrNull == null || cookieTokenOrNull.isBlank()) {
            return Optional.empty();
        }
        String hash = tokenHashUtil.sha256Hex(cookieTokenOrNull);
        return guestSessionRepository.findByTokenHashAndExpiresAtAfter(hash, LocalDateTime.now());
    }

    private LocalDateTime newExpiry() {
        return LocalDateTime.now().plusDays(ttlDays);
    }
}
