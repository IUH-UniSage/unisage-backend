package com.unisage.backend.service.conversation;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.unisage.backend.dto.response.ContinueShareResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.ShareListItemResponse;
import com.unisage.backend.dto.response.ShareResponse;
import com.unisage.backend.dto.response.SharedConversationResponse;
import com.unisage.backend.dto.response.SharedMessageResponse;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.ConversationShare;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.MsgStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.ConversationShareRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.utils.SecurityUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * UNISAGE-107. Transactions are explicit ({@link TransactionTemplate}) rather than
 * {@code @Transactional} because "Tiếp tục" must recover from losing the unique-index race on
 * {@code (source_share_id, user_id)} - which only surfaces once the failed transaction has rolled
 * back - by reading the winner's copy in a fresh transaction.
 */
@Service
@Slf4j
public class ConversationShareServiceImpl implements ConversationShareService {

    static final int TOKEN_BYTES = 32;

    static final String CALCULATION_FEEDBACK_KEY = "calculation_feedback";
    static final String CLARIFICATION_KEY = "clarification";
    static final String CLARIFICATION_OPEN = "open";
    static final String CLARIFICATION_CANCELLED = "cancelled";
    /** Storage keys must never leave through a link; citations aren't supposed to carry them anyway. */
    private static final List<String> CITATION_PRIVATE_KEYS = List.of("objectKey", "object_key");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ConversationShareRepository shareRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;

    public ConversationShareServiceImpl(ConversationShareRepository shareRepository,
            ConversationRepository conversationRepository, MessageRepository messageRepository,
            UserRepository userRepository, SecurityUtil securityUtil,
            PlatformTransactionManager transactionManager) {
        this.shareRepository = shareRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.securityUtil = securityUtil;
        this.tx = new TransactionTemplate(transactionManager);
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    @Override
    public ShareResponse create(UUID conversationId) {
        UUID me = securityUtil.getCurrentUserId();
        return tx.execute(status -> {
            Conversation conversation = conversationRepository.findById(conversationId)
                    .filter(c -> c.getDeletedAt() == null)
                    .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));
            if (conversation.getUser() == null) {
                throw new AppException(ErrorCode.SHARE_GUEST_CONVERSATION);
            }
            if (!conversation.getUser().getId().equals(me)) {
                throw new AppException(ErrorCode.CONVERSATION_NOT_FOUND);
            }

            // Read back from the database, so cutoffAt has the stored (microsecond) precision.
            Message cutoff = messageRepository.findLastCompleted(conversationId)
                    .orElseThrow(() -> new AppException(ErrorCode.SHARE_EMPTY_CONVERSATION));
            int messageCount = messageRepository.countSnapshot(conversationId, cutoff.getCreatedAt(), cutoff.getId());

            ConversationShare share = shareRepository.save(ConversationShare.builder()
                    .token(newToken())
                    .conversation(conversation)
                    .owner(conversation.getUser())
                    .title(conversation.getTitle())
                    .cutoffMessageId(cutoff.getId())
                    .cutoffAt(cutoff.getCreatedAt())
                    .messageCount(messageCount)
                    .build());

            return ShareResponse.builder()
                    .id(share.getId())
                    .token(share.getToken())
                    .title(share.getTitle())
                    .messageCount(share.getMessageCount())
                    .createdAt(share.getCreatedAt())
                    .build();
        });
    }

    @Override
    public PageResponse<List<ShareListItemResponse>> listMine(Pageable pageable) {
        UUID me = securityUtil.getCurrentUserId();
        // Always newest first, whatever ?sort= says; id breaks ties so pages never overlap.
        Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return readOnlyTx.execute(status -> {
            Page<ConversationShare> page = shareRepository.findActiveByOwnerId(me, newestFirst);
            return PageResponse.fromPage(page, share -> ShareListItemResponse.builder()
                    .id(share.getId())
                    .token(share.getToken())
                    .title(share.getTitle())
                    .conversationId(share.getConversation().getId())
                    .messageCount(share.getMessageCount())
                    .createdAt(share.getCreatedAt())
                    .build());
        });
    }

    @Override
    public void revoke(UUID shareId) {
        UUID me = securityUtil.getCurrentUserId();
        tx.executeWithoutResult(status -> {
            ConversationShare share = shareRepository.findByIdAndOwnerId(shareId, me)
                    .orElseThrow(() -> new AppException(ErrorCode.SHARE_NOT_FOUND));
            if (share.getRevokedAt() == null) {
                share.setRevokedAt(LocalDateTime.now());
                shareRepository.save(share);
            }
        });
    }

    @Override
    public int revokeAll() {
        UUID me = securityUtil.getCurrentUserId();
        Integer revoked = tx.execute(status -> shareRepository.revokeAllByOwnerId(me, LocalDateTime.now()));
        return revoked != null ? revoked : 0;
    }

    @Override
    public SharedConversationResponse view(String token) {
        UUID me = securityUtil.getCurrentUserId();
        return readOnlyTx.execute(status -> {
            ConversationShare share = findActive(token);
            boolean isOwner = share.getOwner().getId().equals(me);
            List<SharedMessageResponse> messages = snapshotOf(share).stream()
                    .map(m -> SharedMessageResponse.builder()
                            .id(m.getId())
                            .role(m.getRole())
                            .content(m.getContent())
                            .citations(sanitizeCitations(m.getCitations()))
                            .metadata(stripFeedback(m.getMetadata()))
                            .createdAt(m.getCreatedAt())
                            .build())
                    .toList();
            return SharedConversationResponse.builder()
                    .title(share.getTitle())
                    .createdAt(share.getCreatedAt())
                    .isOwner(isOwner)
                    .conversationId(isOwner ? share.getConversation().getId() : null)
                    .messages(messages)
                    .build();
        });
    }

    @Override
    public ContinueShareResponse continueFromShare(String token) {
        UUID me = securityUtil.getCurrentUserId();
        try {
            return tx.execute(status -> copyFor(token, me));
        } catch (DataIntegrityViolationException e) {
            // Lost the race on uq_conversations_source_share_user_active (a double-click, two tabs):
            // our transaction rolled back, the other one committed a copy - hand that one back.
            log.debug("Concurrent continue of the same share by user {} - returning the existing copy", me);
            ContinueShareResponse existing = readOnlyTx.execute(status -> {
                ConversationShare share = findActive(token);
                return conversationRepository.findActiveCopy(share.getId(), me)
                        .map(c -> new ContinueShareResponse(c.getId()))
                        .orElse(null);
            });
            if (existing == null) {
                throw e;
            }
            return existing;
        }
    }

    /**
     * One transaction: the new conversation, then a copy of each snapshot message in order (spec
     * §5.3). The conversation row is flushed first so a concurrent duplicate fails on the unique
     * index before any message is copied.
     */
    private ContinueShareResponse copyFor(String token, UUID me) {
        ConversationShare share = findActive(token);
        var existing = conversationRepository.findActiveCopy(share.getId(), me);
        if (existing.isPresent()) {
            return new ContinueShareResponse(existing.get().getId());
        }

        User caller = userRepository.findById(me).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        Conversation copy = conversationRepository.saveAndFlush(Conversation.builder()
                .user(caller)
                .title(share.getTitle())
                .sourceShare(share)
                .build());

        for (Message original : snapshotOf(share)) {
            Message saved = messageRepository.save(copyOf(original, copy));
            // created_at comes from auditing at persist time; spacing the saves keeps the copies in
            // the original order under ORDER BY created_at (same technique as startTurn).
            CreatedAtSpacing.waitPast(saved);
        }
        return new ContinueShareResponse(copy.getId());
    }

    /**
     * Keeps what the reader needs to see the answers (content, citations, calculation tables, the
     * model shown); drops what belongs to the original owner: calculation_feedback, and - by not
     * copying them at all - tickets, message traces and calculation traces. A clarification panel
     * still waiting for an answer is closed: its pending state lives with the original
     * conversation on the agent side, so answering it in the copy could not work.
     */
    static Message copyOf(Message original, Conversation into) {
        return Message.builder()
                .conversation(into)
                .role(original.getRole())
                .content(original.getContent())
                .status(MsgStatus.COMPLETED)
                .chatModel(original.getChatModel())
                .citations(sanitizeCitations(original.getCitations()))
                .retrievalScore(original.getRetrievalScore())
                .responseTimeMs(original.getResponseTimeMs())
                .metadata(closeOpenClarification(stripFeedback(original.getMetadata())))
                .build();
    }

    private ConversationShare findActive(String token) {
        if (token == null || token.isBlank()) {
            throw new AppException(ErrorCode.SHARE_NOT_FOUND);
        }
        return shareRepository.findActiveByToken(token)
                .orElseThrow(() -> new AppException(ErrorCode.SHARE_NOT_FOUND));
    }

    private List<Message> snapshotOf(ConversationShare share) {
        return messageRepository.findSnapshot(
                share.getConversation().getId(), share.getCutoffAt(), share.getCutoffMessageId());
    }

    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    static Map<String, Object> stripFeedback(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        copy.remove(CALCULATION_FEEDBACK_KEY);
        return copy.isEmpty() ? null : copy;
    }

    static Map<String, Object> closeOpenClarification(Map<String, Object> metadata) {
        if (metadata == null || !(metadata.get(CLARIFICATION_KEY) instanceof Map<?, ?> clarification)
                || !CLARIFICATION_OPEN.equals(clarification.get("status"))) {
            return metadata;
        }
        // "cancelled" is the only closed state the panel schema knows (web renders it read-only).
        Map<String, Object> closed = new LinkedHashMap<>();
        clarification.forEach((key, value) -> closed.put(String.valueOf(key), value));
        closed.put("status", CLARIFICATION_CANCELLED);
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        copy.put(CLARIFICATION_KEY, closed);
        return copy;
    }

    static List<Map<String, Object>> sanitizeCitations(List<Map<String, Object>> citations) {
        if (citations == null) {
            return null;
        }
        return citations.stream()
                .map(citation -> {
                    if (citation == null) {
                        return citation;
                    }
                    Map<String, Object> copy = new LinkedHashMap<>(citation);
                    CITATION_PRIVATE_KEYS.forEach(copy::remove);
                    return copy;
                })
                .toList();
    }
}
