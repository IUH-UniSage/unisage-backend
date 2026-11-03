package com.unisage.backend.service.conversation;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unisage.backend.dto.response.ContinueShareResponse;
import com.unisage.backend.dto.response.ShareResponse;
import com.unisage.backend.dto.response.SharedConversationResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.ConversationShare;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.ConversationShareRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.utils.SecurityUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationShareServiceImplTest {

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID READER_ID = UUID.randomUUID();

    private ConversationShareRepository shareRepository;
    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private UserRepository userRepository;
    private SecurityUtil securityUtil;
    private ConversationShareServiceImpl service;

    private User owner;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        shareRepository = mock(ConversationShareRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(MessageRepository.class);
        userRepository = mock(UserRepository.class);
        securityUtil = mock(SecurityUtil.class);
        service = new ConversationShareServiceImpl(shareRepository, conversationRepository, messageRepository,
                userRepository, securityUtil, mock(PlatformTransactionManager.class));

        owner = User.builder().id(OWNER_ID).build();
        conversation = Conversation.builder().id(UUID.randomUUID()).user(owner).title("Học phí K68").build();
        when(shareRepository.save(any(ConversationShare.class))).thenAnswer(inv -> {
            ConversationShare share = inv.getArgument(0);
            if (share.getId() == null) {
                share.setId(UUID.randomUUID());
            }
            return share;
        });
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(conversationRepository.saveAndFlush(any(Conversation.class))).thenAnswer(inv -> {
            Conversation c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
    }

    private void callerIs(UUID id) {
        when(securityUtil.getCurrentUserId()).thenReturn(id);
    }

    // ── create ───────────────────────────────────────────────────────────

    @Test
    void create_owner_snapshotsUpToTheLastCompletedMessage() {
        callerIs(OWNER_ID);
        LocalDateTime cutoffAt = LocalDateTime.of(2026, 11, 2, 10, 0, 0, 123_456_000);
        Message last = message(MsgRole.ASSISTANT, "answer", cutoffAt);
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(messageRepository.findLastCompleted(conversation.getId())).thenReturn(Optional.of(last));
        when(messageRepository.countSnapshot(conversation.getId(), cutoffAt, last.getId())).thenReturn(4);

        ShareResponse response = service.create(conversation.getId());

        ArgumentCaptor<ConversationShare> saved = ArgumentCaptor.forClass(ConversationShare.class);
        verify(shareRepository).save(saved.capture());
        ConversationShare share = saved.getValue();
        assertThat(share.getConversation()).isSameAs(conversation);
        assertThat(share.getOwner()).isSameAs(owner);
        assertThat(share.getTitle()).isEqualTo("Học phí K68");
        assertThat(share.getCutoffMessageId()).isEqualTo(last.getId());
        assertThat(share.getCutoffAt()).isEqualTo(cutoffAt);
        assertThat(share.getMessageCount()).isEqualTo(4);
        assertThat(share.getRevokedAt()).isNull();

        assertThat(response.id()).isEqualTo(share.getId());
        assertThat(response.token()).isEqualTo(share.getToken());
        assertThat(response.title()).isEqualTo("Học phí K68");
        assertThat(response.messageCount()).isEqualTo(4);
    }

    @Test
    void create_someoneElsesConversation_isConversationNotFound() {
        callerIs(READER_ID);
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));

        assertError(() -> service.create(conversation.getId()), ErrorCode.CONVERSATION_NOT_FOUND);
        verify(shareRepository, never()).save(any());
    }

    @Test
    void create_deletedOrUnknownConversation_isConversationNotFound() {
        callerIs(OWNER_ID);
        conversation.setDeletedAt(LocalDateTime.now());
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        UUID unknown = UUID.randomUUID();
        when(conversationRepository.findById(unknown)).thenReturn(Optional.empty());

        assertError(() -> service.create(conversation.getId()), ErrorCode.CONVERSATION_NOT_FOUND);
        assertError(() -> service.create(unknown), ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    void create_guestConversation_isShareGuestConversation() {
        callerIs(OWNER_ID);
        Conversation guest = Conversation.builder().id(UUID.randomUUID())
                .guestSession(GuestSession.builder().id(UUID.randomUUID()).build()).build();
        when(conversationRepository.findById(guest.getId())).thenReturn(Optional.of(guest));

        assertError(() -> service.create(guest.getId()), ErrorCode.SHARE_GUEST_CONVERSATION);
    }

    @Test
    void create_noCompletedMessage_isShareEmptyConversation() {
        callerIs(OWNER_ID);
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(messageRepository.findLastCompleted(conversation.getId())).thenReturn(Optional.empty());

        assertError(() -> service.create(conversation.getId()), ErrorCode.SHARE_EMPTY_CONVERSATION);
        verify(shareRepository, never()).save(any());
    }

    @Test
    void newToken_is256BitUrlSafeBase64WithoutPadding_andUnique() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = ConversationShareServiceImpl.newToken();
            // 32 bytes -> 43 chars; only [A-Za-z0-9_-], so no % ; / . \ that the gateway rejects.
            assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
            tokens.add(token);
        }
        assertThat(tokens).hasSize(200);
    }

    // ── view ─────────────────────────────────────────────────────────────

    @Test
    void view_nonOwner_seesSnapshotWithoutOriginalIdFeedbackOrStorageKeys() throws Exception {
        callerIs(READER_ID);
        ConversationShare share = share();
        when(shareRepository.findActiveByToken("tok")).thenReturn(Optional.of(share));
        Message question = message(MsgRole.USER, "Học phí bao nhiêu?", share.getCutoffAt().minusSeconds(5));
        Message answer = message(MsgRole.ASSISTANT, "8.400.000đ", share.getCutoffAt());
        answer.setMetadata(new LinkedHashMap<>(Map.of(
                "calculation", Map.of("items", List.of()),
                "calculation_feedback", Map.of("T1", Map.of("verdict", "WRONG")))));
        answer.setCitations(List.of(new LinkedHashMap<>(Map.of(
                "index", 1, "title", "Quy chế", "documentId", "d1", "objectKey", "secret/key.pdf"))));
        answer.setRetrievalScore(0.9f);
        answer.setResponseTimeMs(1200);
        when(messageRepository.findSnapshot(conversation.getId(), share.getCutoffAt(), share.getCutoffMessageId()))
                .thenReturn(List.of(question, answer));

        SharedConversationResponse view = service.view("tok");

        assertThat(view.isOwner()).isFalse();
        assertThat(view.conversationId()).isNull();
        assertThat(view.title()).isEqualTo(share.getTitle());
        assertThat(view.messages()).extracting(m -> m.content()).containsExactly("Học phí bao nhiêu?", "8.400.000đ");
        assertThat(view.messages().get(1).metadata()).containsKey("calculation").doesNotContainKey("calculation_feedback");
        assertThat(view.messages().get(1).citations().get(0)).containsEntry("documentId", "d1")
                .doesNotContainKey("objectKey");
        // The stored message itself is untouched.
        assertThat(answer.getMetadata()).containsKey("calculation_feedback");

        String json = mapper().writeValueAsString(view);
        assertThat(json).contains("\"isOwner\":false")
                .doesNotContain("conversationId")
                .doesNotContain(conversation.getId().toString())
                .doesNotContain("calculation_feedback")
                .doesNotContain("objectKey")
                .doesNotContain("trace")
                .doesNotContain("ticketId")
                .doesNotContain("retrievalScore")
                .doesNotContain("responseTimeMs");
    }

    @Test
    void view_owner_getsTheOriginalConversationId() throws Exception {
        callerIs(OWNER_ID);
        ConversationShare share = share();
        when(shareRepository.findActiveByToken("tok")).thenReturn(Optional.of(share));
        when(messageRepository.findSnapshot(any(), any(), any())).thenReturn(List.of());

        SharedConversationResponse view = service.view("tok");

        assertThat(view.isOwner()).isTrue();
        assertThat(view.conversationId()).isEqualTo(conversation.getId());
        assertThat(mapper().writeValueAsString(view)).contains("\"isOwner\":true")
                .contains("\"conversationId\":\"" + conversation.getId() + "\"");
    }

    @Test
    void view_unknownRevokedOrDeleted_isShareNotFound() {
        callerIs(READER_ID);
        // findActiveByToken already filters out revoked links and deleted conversations.
        when(shareRepository.findActiveByToken(any())).thenReturn(Optional.empty());

        assertError(() -> service.view("nope"), ErrorCode.SHARE_NOT_FOUND);
        assertError(() -> service.view(""), ErrorCode.SHARE_NOT_FOUND);
    }

    // ── manage ───────────────────────────────────────────────────────────

    @Test
    void listMine_isAlwaysNewestFirstForTheCaller() {
        callerIs(OWNER_ID);
        ConversationShare share = share();
        share.setCreatedAt(LocalDateTime.now());
        when(shareRepository.findActiveByOwnerId(eq(OWNER_ID), any()))
                .thenAnswer(inv -> new PageImpl<>(List.of(share), inv.getArgument(1), 1));

        var page = service.listMine(PageRequest.of(1, 5, Sort.by("title")));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(shareRepository).findActiveByOwnerId(eq(OWNER_ID), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageable.getValue().getSort()).containsExactly(
                Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        assertThat(page.data()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(share.getId());
            assertThat(item.token()).isEqualTo("tok");
            assertThat(item.conversationId()).isEqualTo(conversation.getId());
            assertThat(item.messageCount()).isEqualTo(2);
        });
        assertThat(page.page()).isEqualTo(2); // 1-based like every PageResponse
    }

    @Test
    void revoke_ownLink_setsRevokedAt_andIsIdempotent() {
        callerIs(OWNER_ID);
        ConversationShare share = share();
        when(shareRepository.findByIdAndOwnerId(share.getId(), OWNER_ID)).thenReturn(Optional.of(share));

        service.revoke(share.getId());
        LocalDateTime revokedAt = share.getRevokedAt();
        service.revoke(share.getId());

        assertThat(revokedAt).isNotNull();
        assertThat(share.getRevokedAt()).isEqualTo(revokedAt);
        verify(shareRepository, times(1)).save(share);
    }

    @Test
    void revoke_someoneElsesLink_isShareNotFound() {
        callerIs(READER_ID);
        UUID shareId = UUID.randomUUID();
        when(shareRepository.findByIdAndOwnerId(shareId, READER_ID)).thenReturn(Optional.empty());

        assertError(() -> service.revoke(shareId), ErrorCode.SHARE_NOT_FOUND);
    }

    @Test
    void revokeAll_returnsHowManyWereRevoked() {
        callerIs(OWNER_ID);
        when(shareRepository.revokeAllByOwnerId(eq(OWNER_ID), any())).thenReturn(3);

        assertThat(service.revokeAll()).isEqualTo(3);
    }

    // ── continue ─────────────────────────────────────────────────────────

    @Test
    void continue_existingLiveCopy_isReturnedWithoutCopyingAgain() {
        callerIs(READER_ID);
        ConversationShare share = share();
        when(shareRepository.findActiveByToken("tok")).thenReturn(Optional.of(share));
        Conversation copy = Conversation.builder().id(UUID.randomUUID()).build();
        when(conversationRepository.findActiveCopy(share.getId(), READER_ID)).thenReturn(Optional.of(copy));

        ContinueShareResponse response = service.continueFromShare("tok");

        assertThat(response.conversationId()).isEqualTo(copy.getId());
        verify(conversationRepository, never()).saveAndFlush(any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void continue_revokedOrUnknownLink_isShareNotFound() {
        callerIs(READER_ID);
        when(shareRepository.findActiveByToken("tok")).thenReturn(Optional.empty());

        assertError(() -> service.continueFromShare("tok"), ErrorCode.SHARE_NOT_FOUND);
        verify(conversationRepository, never()).saveAndFlush(any());
    }

    @Test
    void continue_createsTheCallersCopy_inOrder_withOnlyTheFieldsTheSpecKeeps() {
        callerIs(READER_ID);
        User reader = User.builder().id(READER_ID).build();
        when(userRepository.findById(READER_ID)).thenReturn(Optional.of(reader));
        ConversationShare share = share();
        when(shareRepository.findActiveByToken("tok")).thenReturn(Optional.of(share));
        when(conversationRepository.findActiveCopy(share.getId(), READER_ID)).thenReturn(Optional.empty());

        ChatModel model = ChatModel.builder().id(UUID.randomUUID()).build();
        Message question = message(MsgRole.USER, "Q1", share.getCutoffAt().minusSeconds(3));
        question.setMetadata(Map.of("clarification_answers", Map.of("panel_id", "p0")));
        Message answer = message(MsgRole.ASSISTANT, "A1", share.getCutoffAt().minusSeconds(2));
        answer.setChatModel(model);
        answer.setCitations(List.of(Map.of("index", 1, "title", "Quy chế", "documentId", "d1")));
        answer.setRetrievalScore(0.7f);
        answer.setResponseTimeMs(900);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("calculation", Map.of("items", List.of(Map.of("item_id", "T1"))));
        metadata.put("calculation_feedback", Map.of("T1", Map.of("verdict", "WRONG")));
        answer.setMetadata(metadata);
        Message panel = message(MsgRole.ASSISTANT, "Bạn thuộc khoá nào?", share.getCutoffAt());
        Map<String, Object> clarification = new LinkedHashMap<>();
        clarification.put("schema_version", 1);
        clarification.put("status", "open");
        clarification.put("panel", Map.of("panel_id", "p1"));
        panel.setMetadata(new LinkedHashMap<>(Map.of("clarification", clarification)));
        when(messageRepository.findSnapshot(conversation.getId(), share.getCutoffAt(), share.getCutoffMessageId()))
                .thenReturn(List.of(question, answer, panel));

        ContinueShareResponse response = service.continueFromShare("tok");

        ArgumentCaptor<Conversation> createdConversation = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).saveAndFlush(createdConversation.capture());
        Conversation copy = createdConversation.getValue();
        assertThat(response.conversationId()).isEqualTo(copy.getId());
        assertThat(copy.getUser()).isSameAs(reader);
        assertThat(copy.getGuestSession()).isNull();
        assertThat(copy.getTitle()).isEqualTo(share.getTitle());
        assertThat(copy.getSourceShare()).isSameAs(share);

        ArgumentCaptor<Message> copied = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(3)).save(copied.capture());
        List<Message> copies = copied.getAllValues();
        assertThat(copies).extracting(Message::getContent).containsExactly("Q1", "A1", "Bạn thuộc khoá nào?");
        assertThat(copies).allSatisfy(m -> {
            assertThat(m.getConversation()).isSameAs(copy);
            assertThat(m.getId()).isNull();
            assertThat(m.getStatus()).isEqualTo(MsgStatus.COMPLETED);
            assertThat(m.getCreatedAt()).isNull(); // stamped by auditing, never by hand
        });
        assertThat(copies.get(0).getMetadata()).containsKey("clarification_answers");

        Message a1 = copies.get(1);
        assertThat(a1.getRole()).isEqualTo(MsgRole.ASSISTANT);
        assertThat(a1.getChatModel()).isSameAs(model);
        assertThat(a1.getCitations()).isEqualTo(answer.getCitations());
        assertThat(a1.getRetrievalScore()).isEqualTo(0.7f);
        assertThat(a1.getResponseTimeMs()).isEqualTo(900);
        assertThat(a1.getMetadata()).containsKey("calculation").doesNotContainKey("calculation_feedback");

        @SuppressWarnings("unchecked")
        Map<String, Object> copiedPanel = (Map<String, Object>) copies.get(2).getMetadata().get("clarification");
        assertThat(copiedPanel).containsEntry("status", "cancelled")
                .containsEntry("schema_version", 1)
                .containsEntry("panel", Map.of("panel_id", "p1"));
        // The original panel is still open for its owner.
        assertThat(clarification).containsEntry("status", "open");
    }

    @Test
    void continue_lostTheUniqueIndexRace_returnsTheWinnersCopy() {
        callerIs(READER_ID);
        when(userRepository.findById(READER_ID)).thenReturn(Optional.of(User.builder().id(READER_ID).build()));
        ConversationShare share = share();
        when(shareRepository.findActiveByToken("tok")).thenReturn(Optional.of(share));
        Conversation winner = Conversation.builder().id(UUID.randomUUID()).build();
        when(conversationRepository.findActiveCopy(share.getId(), READER_ID))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(conversationRepository.saveAndFlush(any(Conversation.class)))
                .thenThrow(new DataIntegrityViolationException("uq_conversations_source_share_user_active"));

        assertThat(service.continueFromShare("tok").conversationId()).isEqualTo(winner.getId());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void continue_integrityErrorWithoutAnyCopy_isRethrown() {
        callerIs(READER_ID);
        when(userRepository.findById(READER_ID)).thenReturn(Optional.of(User.builder().id(READER_ID).build()));
        ConversationShare share = share();
        when(shareRepository.findActiveByToken("tok")).thenReturn(Optional.of(share));
        when(conversationRepository.findActiveCopy(share.getId(), READER_ID)).thenReturn(Optional.empty());
        when(conversationRepository.saveAndFlush(any(Conversation.class)))
                .thenThrow(new DataIntegrityViolationException("boom"));

        assertThatThrownBy(() -> service.continueFromShare("tok")).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private ConversationShare share() {
        return ConversationShare.builder()
                .id(UUID.randomUUID())
                .token("tok")
                .conversation(conversation)
                .owner(owner)
                .title("Học phí K68")
                .cutoffMessageId(UUID.randomUUID())
                .cutoffAt(LocalDateTime.of(2026, 11, 2, 10, 0))
                .messageCount(2)
                .build();
    }

    private Message message(MsgRole role, String content, LocalDateTime createdAt) {
        Message message = Message.builder()
                .id(UUID.randomUUID())
                .conversation(conversation)
                .role(role)
                .content(content)
                .status(MsgStatus.COMPLETED)
                .build();
        message.setCreatedAt(createdAt);
        return message;
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(expected);
    }

}
