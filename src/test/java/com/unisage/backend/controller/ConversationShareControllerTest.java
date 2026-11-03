package com.unisage.backend.controller;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import com.unisage.backend.dto.request.CreateShareRequest;
import com.unisage.backend.dto.response.ContinueShareResponse;
import com.unisage.backend.dto.response.ShareResponse;
import com.unisage.backend.service.conversation.ConversationShareService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationShareControllerTest {

    private final ConversationShareService service = mock(ConversationShareService.class);
    private final ConversationShareController controller = new ConversationShareController(service);

    @Test
    void create_is201WithTheShare() {
        UUID conversationId = UUID.randomUUID();
        ShareResponse share = ShareResponse.builder().id(UUID.randomUUID()).token("t").title("x")
                .messageCount(2).createdAt(LocalDateTime.now()).build();
        when(service.create(conversationId)).thenReturn(share);

        var response = controller.create(new CreateShareRequest(conversationId));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().code()).isEqualTo(1000);
        assertThat(response.getBody().data()).isEqualTo(share);
    }

    @Test
    void revokeAll_returnsTheCountAsData() {
        when(service.revokeAll()).thenReturn(4);

        var response = controller.revokeAll();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data()).isEqualTo(4);
    }

    @Test
    void revoke_view_continue_list_delegateToTheService() {
        UUID shareId = UUID.randomUUID();
        UUID copyId = UUID.randomUUID();
        when(service.continueFromShare("tok")).thenReturn(new ContinueShareResponse(copyId));

        controller.revoke(shareId);
        controller.view("tok");
        controller.listMine(PageRequest.of(0, 10));
        var continued = controller.continueFromShare("tok");

        verify(service).revoke(shareId);
        verify(service).view("tok");
        verify(service).listMine(PageRequest.of(0, 10));
        assertThat(continued.getBody().data().conversationId()).isEqualTo(copyId);
    }
}
