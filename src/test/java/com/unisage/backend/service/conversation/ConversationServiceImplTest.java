package com.unisage.backend.service.conversation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.unisage.backend.dto.response.ConversationResponse;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.ConversationShare;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.User;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.ConversationShareRepository;
import com.unisage.backend.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** UNISAGE-107 §4 ownership fixes and R11 (deleting a conversation revokes its links). */
class ConversationServiceImplTest {

    private static final UUID OWNER_ID = UUID.randomUUID();

    private ConversationRepository conversationRepository;
    private UserRepository userRepository;
    private ConversationShareRepository shareRepository;
    private ConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        userRepository = mock(UserRepository.class);
        shareRepository = mock(ConversationShareRepository.class);
        service = new ConversationServiceImpl(conversationRepository, userRepository, shareRepository);
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── softDelete ───────────────────────────────────────────────────────

    @Test
    void softDelete_owner_deletesAndRevokesTheConversationsLinks() {
        Conversation conversation = owned();
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));

        service.softDelete(conversation.getId(), OWNER_ID);

        assertThat(conversation.getDeletedAt()).isNotNull();
        verify(conversationRepository).save(conversation);
        verify(shareRepository).revokeAllByConversationId(conversation.getId(), conversation.getDeletedAt());
    }

    @Test
    void softDelete_otherUser_isNotFoundAndChangesNothing() {
        Conversation conversation = owned();
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));

        assertError(() -> service.softDelete(conversation.getId(), UUID.randomUUID()), ErrorCode.CONVERSATION_NOT_FOUND);
        assertThat(conversation.getDeletedAt()).isNull();
        verify(conversationRepository, never()).save(any());
        verify(shareRepository, never()).revokeAllByConversationId(any(), any());
    }

    @Test
    void softDelete_guestConversationOrUnknown_isNotFound() {
        Conversation guest = Conversation.builder().id(UUID.randomUUID())
                .guestSession(GuestSession.builder().id(UUID.randomUUID()).build()).build();
        when(conversationRepository.findById(guest.getId())).thenReturn(Optional.of(guest));
        UUID unknown = UUID.randomUUID();
        when(conversationRepository.findById(unknown)).thenReturn(Optional.empty());

        assertError(() -> service.softDelete(guest.getId(), OWNER_ID), ErrorCode.CONVERSATION_NOT_FOUND);
        assertError(() -> service.softDelete(unknown, OWNER_ID), ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    void softDelete_alreadyDeletedByOwner_isANoOp() {
        Conversation conversation = owned();
        LocalDateTime deletedAt = LocalDateTime.now().minusDays(1);
        conversation.setDeletedAt(deletedAt);
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));

        service.softDelete(conversation.getId(), OWNER_ID);

        assertThat(conversation.getDeletedAt()).isEqualTo(deletedAt);
        verify(conversationRepository, never()).save(any());
    }

    // ── claim ────────────────────────────────────────────────────────────

    @Test
    void claim_sameGuestSession_attachesToTheUser() {
        GuestSession session = GuestSession.builder().id(UUID.randomUUID()).build();
        Conversation guest = Conversation.builder().id(UUID.randomUUID()).guestSession(session).build();
        when(conversationRepository.findById(guest.getId())).thenReturn(Optional.of(guest));
        User user = User.builder().id(OWNER_ID).build();
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user));

        ConversationResponse response = service.claim(guest.getId(), OWNER_ID,
                GuestSession.builder().id(session.getId()).build());

        assertThat(guest.getUser()).isSameAs(user);
        assertThat(guest.getGuestSession()).isNull();
        assertThat(response.userId()).isEqualTo(OWNER_ID);
    }

    @Test
    void claim_missingOrOtherGuestSession_isNotFound() {
        GuestSession session = GuestSession.builder().id(UUID.randomUUID()).build();
        Conversation guest = Conversation.builder().id(UUID.randomUUID()).guestSession(session).build();
        when(conversationRepository.findById(guest.getId())).thenReturn(Optional.of(guest));

        assertError(() -> service.claim(guest.getId(), OWNER_ID, null), ErrorCode.CONVERSATION_NOT_FOUND);
        assertError(() -> service.claim(guest.getId(), OWNER_ID,
                GuestSession.builder().id(UUID.randomUUID()).build()), ErrorCode.CONVERSATION_NOT_FOUND);
        assertThat(guest.getUser()).isNull();
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void claim_alreadyOwned_isAlreadyClaimed() {
        Conversation conversation = owned();
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));

        assertError(() -> service.claim(conversation.getId(), OWNER_ID, null), ErrorCode.CONVERSATION_ALREADY_CLAIMED);
    }

    // ── response ─────────────────────────────────────────────────────────

    @Test
    void getByUser_exposesSourceShareIdOfACopy() {
        UUID shareId = UUID.randomUUID();
        Conversation copy = owned();
        copy.setSourceShare(ConversationShare.builder().id(shareId).build());
        Conversation plain = owned();
        when(conversationRepository.findActiveByUserId(eq(OWNER_ID))).thenReturn(List.of(copy, plain));

        assertThat(service.getByUser(OWNER_ID)).extracting(ConversationResponse::sourceShareId)
                .containsExactly(shareId, null);
    }

    private Conversation owned() {
        return Conversation.builder().id(UUID.randomUUID()).user(User.builder().id(OWNER_ID).build())
                .title("t").build();
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(expected);
    }
}
