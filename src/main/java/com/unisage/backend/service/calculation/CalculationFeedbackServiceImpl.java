package com.unisage.backend.service.calculation;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.dto.request.CalculationFeedbackRequest;
import com.unisage.backend.dto.response.CalculationFeedbackResponse;
import com.unisage.backend.entity.CalculationTrace;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.Ticket;
import com.unisage.backend.entity.enums.CalculationVerdict;
import com.unisage.backend.entity.enums.CalculationWrongReason;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.TicketStatus;
import com.unisage.backend.entity.enums.TicketType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.CalculationTraceRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.TicketRepository;
import com.unisage.backend.service.guestsession.GuestSessionService;
import com.unisage.backend.service.ticket.TicketDescriptions;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

/**
 * SPEC-calculation-node §7.3 / contracts/chat-sse.md §5b. The verdict goes into
 * {@code metadata.calculation_feedback[itemId] = {verdict, reason, at}} (never the note); a signed-in
 * caller's WRONG verdict also files one {@code AI_CALCULATION_WRONG} ticket per item, whose
 * description carries the staff-only trace from {@code calculation_traces}.
 */
@Service
@RequiredArgsConstructor
public class CalculationFeedbackServiceImpl implements CalculationFeedbackService {

    /** Body limit of a feedback request (contracts/chat-sse.md §5b). */
    static final long MAX_REQUEST_BYTES = 2 * 1024;

    /** Resolution written when the user's own CORRECT verdict closes the item's ticket. */
    public static final String USER_SWITCHED_TO_CORRECT = "Người dùng đổi đánh giá thành Đúng";

    static final int MAX_BODY_BYTES = 2 * 1024;

    private static final String FEEDBACK_KEY = "calculation_feedback";

    private final MessageRepository messageRepository;
    private final TicketRepository ticketRepository;
    private final CalculationTraceRepository calculationTraceRepository;
    private final GuestSessionService guestSessionService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    @Transactional
    public CalculationFeedbackResponse submit(UUID messageId, CalculationFeedbackRequest request,
                                              UUID callerId, String guestSessionToken) {
        // Overridden (not the interface default) so the transaction applies: a default method
        // calling the 5-argument overload would bypass the Spring proxy.
        return submit(messageId, request, callerId, guestSessionToken, 0L);
    }

    @Override
    @Transactional
    public CalculationFeedbackResponse submit(UUID messageId, CalculationFeedbackRequest request,
                                              UUID callerId, String guestSessionToken,
                                              long requestBytes) {
        if (requestBytes > MAX_REQUEST_BYTES) {
            throw new AppException(ErrorCode.CALCULATION_FEEDBACK_INVALID);
        }
        String note = validate(request);

        // Locked: the metadata write below is a read-modify-write of the whole jsonb value.
        Message message = messageRepository.findByIdForUpdate(messageId)
                .orElseThrow(() -> new AppException(ErrorCode.CALCULATION_ITEM_NOT_FOUND));
        // Not the caller's message, not an assistant message, or no retrieved+computed item: all the
        // same 404, so a caller cannot probe other users' messages.
        if (message.getRole() != MsgRole.ASSISTANT
                || !isOwnedByCaller(message.getConversation(), callerId, guestSessionToken)) {
            throw new AppException(ErrorCode.CALCULATION_ITEM_NOT_FOUND);
        }
        Map<?, ?> item = findFeedbackableItem(message.getMetadata(), request.itemId())
                .orElseThrow(() -> new AppException(ErrorCode.CALCULATION_ITEM_NOT_FOUND));

        boolean ticketCreated = false;
        // Guests cannot own a ticket (tickets.user_id NOT NULL); their verdict lives in metadata only.
        if (message.getConversation().getUser() != null) {
            ticketCreated = applyToTicket(message, item, request, note);
        }

        writeFeedback(message, request);

        return CalculationFeedbackResponse.builder()
                .itemId(request.itemId())
                .verdict(request.verdict())
                .reason(request.reason())
                .ticketCreated(ticketCreated)
                .build();
    }

    /** Cross-field rules plus the 2 KB body cap; returns the trimmed note (null when blank). */
    private String validate(CalculationFeedbackRequest request) {
        try {
            if (objectMapper.writeValueAsBytes(request).length > MAX_BODY_BYTES) {
                throw new AppException(ErrorCode.CALCULATION_FEEDBACK_INVALID);
            }
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.CALCULATION_FEEDBACK_INVALID);
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        boolean wrong = request.verdict() == CalculationVerdict.WRONG;
        if (wrong != (request.reason() != null)) {
            throw new AppException(ErrorCode.CALCULATION_FEEDBACK_INVALID);
        }
        if (request.reason() == CalculationWrongReason.OTHER && note == null) {
            throw new AppException(ErrorCode.CALCULATION_FEEDBACK_INVALID);
        }
        return note;
    }

    /** Same ownership rule as start turn / send: the owner, or the guest session that created it. */
    private boolean isOwnedByCaller(Conversation conversation, UUID callerId, String guestSessionToken) {
        if (conversation.getUser() != null) {
            return callerId != null && conversation.getUser().getId().equals(callerId);
        }
        if (callerId != null || conversation.getGuestSession() == null) {
            return false;
        }
        UUID guestSessionId = conversation.getGuestSession().getId();
        return guestSessionService.resolveAndTouch(guestSessionToken)
                .map(resolution -> resolution.session().getId().equals(guestSessionId))
                .orElse(false);
    }

    /** {@code metadata.calculation.items[]} entry with this item_id, mode retrieved, status computed. */
    private static Optional<Map<?, ?>> findFeedbackableItem(Map<String, Object> metadata, String itemId) {
        if (metadata == null || !(metadata.get("calculation") instanceof Map<?, ?> calculation)
                || !(calculation.get("items") instanceof List<?> items)) {
            return Optional.empty();
        }
        for (Object candidate : items) {
            if (candidate instanceof Map<?, ?> item
                    && itemId.equals(item.get("item_id"))
                    && "retrieved".equals(item.get("mode"))
                    && "computed".equals(item.get("status"))) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    /**
     * WRONG creates the item's ticket, or rewrites an unfinished one with the new reason/note.
     * CORRECT closes an unfinished one. A ticket staff already resolved/closed locks the verdict
     * (409). A ticket the user closed themselves by switching to CORRECT is not "handled" - switching
     * back to WRONG reopens it, since the per-item unique index leaves no room for a second one.
     */
    private boolean applyToTicket(Message message, Map<?, ?> item, CalculationFeedbackRequest request,
                                  String note) {
        Optional<Ticket> existing = ticketRepository.findByMessageIdAndCalculationItemId(
                message.getId(), request.itemId());
        boolean closedByUser = existing.map(CalculationFeedbackServiceImpl::isClosedByUser).orElse(false);
        boolean handledByStaff = existing
                .map(t -> t.getStatus() == TicketStatus.RESOLVED
                        || (t.getStatus() == TicketStatus.CLOSED && !isClosedByUser(t)))
                .orElse(false);
        if (handledByStaff) {
            throw new AppException(ErrorCode.CALCULATION_FEEDBACK_LOCKED);
        }

        if (request.verdict() == CalculationVerdict.CORRECT) {
            existing.filter(t -> !closedByUser).ifPresent(ticket -> {
                ticket.setStatus(TicketStatus.CLOSED);
                ticket.setResolution(USER_SWITCHED_TO_CORRECT);
                ticketRepository.save(ticket);
            });
            return false;
        }

        Ticket ticket = existing.orElseGet(() -> Ticket.builder()
                .user(message.getConversation().getUser())
                .message(message)
                .type(TicketType.AI_CALCULATION_WRONG)
                .calculationItemId(request.itemId())
                .build());
        if (closedByUser) {
            ticket.setStatus(TicketStatus.OPEN);
            ticket.setResolution(null);
        }
        ticket.setTitle("Tính sai (" + request.itemId() + "): " + request.reason().getDisplayName());
        ticket.setDescription(describe(message.getId(), item, request, note));
        ticketRepository.save(ticket);
        return true;
    }

    private static boolean isClosedByUser(Ticket ticket) {
        return ticket.getStatus() == TicketStatus.CLOSED && USER_SWITCHED_TO_CORRECT.equals(ticket.getResolution());
    }

    private String describe(UUID messageId, Map<?, ?> item, CalculationFeedbackRequest request, String note) {
        Optional<CalculationTrace> trace = calculationTraceRepository.findByMessageIdAndItemId(
                messageId, request.itemId());
        String runId = trace.map(CalculationTrace::getRunId)
                .orElseGet(() -> item.get("run_id") == null ? null : String.valueOf(item.get("run_id")));

        StringBuilder out = new StringBuilder();
        out.append("Người dùng đánh giá kết quả tính ").append(request.itemId()).append(" là Sai.\n");
        out.append("Lý do: ").append(request.reason().getDisplayName())
                .append(" (").append(request.reason().name()).append(")\n");
        out.append("Ghi chú: ").append(note == null ? "(không có)" : note).append('\n');
        appendIfPresent(out, "Kết quả đã hiển thị", item.get("result_summary"));
        if (item.get("source_summary") instanceof Map<?, ?> source) {
            appendIfPresent(out, "Nguồn đã hiển thị", joinNonNull(source.get("title"), source.get("heading")));
        }
        out.append("run_id: ").append(runId == null ? "(không rõ)" : runId);

        out.append(TicketDescriptions.STAFF_ONLY_MARKER);
        if (trace.isEmpty()) {
            out.append("Không có trace cho run_id ").append(runId == null ? "(không rõ)" : runId).append('.');
        } else {
            appendTrace(out, trace.get().getTrace());
        }
        return out.toString();
    }

    /** A readable summary of the fields staff look at first, then the whole trace as pretty JSON. */
    private void appendTrace(StringBuilder out, Map<String, Object> trace) {
        out.append("Trace (calculation_traces):\n");
        appendIfPresent(out, "Câu hỏi gốc", trace.get("question_raw"));
        appendIfPresent(out, "Công thức", trace.get("formula_id"));
        appendIfPresent(out, "Biểu thức", trace.get("expression"));
        appendNamedValues(out, "Đầu vào", trace.get("inputs"));
        appendNamedValues(out, "Kết quả", trace.get("outputs"));
        if (trace.get("source") instanceof Map<?, ?> source) {
            appendIfPresent(out, "Nguồn", joinNonNull(source.get("source"), source.get("heading_path"),
                    source.get("chunk_id")));
        }
        appendIfPresent(out, "Câu trích", trace.get("source_quote"));
        out.append("\nTrace đầy đủ (JSON):\n");
        try {
            out.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(trace));
        } catch (JsonProcessingException e) {
            out.append(trace);
        }
    }

    private static void appendNamedValues(StringBuilder out, String label, Object values) {
        if (!(values instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        out.append(label).append(":\n");
        for (Object value : list) {
            if (value instanceof Map<?, ?> entry) {
                Object name = entry.get("label") != null ? entry.get("label") : entry.get("name");
                out.append("  - ").append(name).append(" = ").append(entry.get("value")).append('\n');
            }
        }
    }

    private static void appendIfPresent(StringBuilder out, String label, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            out.append(label).append(": ").append(value).append('\n');
        }
    }

    private static String joinNonNull(Object... parts) {
        StringBuilder joined = new StringBuilder();
        for (Object part : parts) {
            if (part == null) {
                continue;
            }
            String text = part instanceof List<?> list
                    ? String.join(" › ", list.stream().map(String::valueOf).toList())
                    : String.valueOf(part);
            if (!text.isBlank()) {
                joined.append(joined.isEmpty() ? "" : " · ").append(text);
            }
        }
        return joined.isEmpty() ? null : joined.toString();
    }

    /** Copies both levels: Hibernate only sees a changed jsonb value through a new reference. */
    private void writeFeedback(Message message, CalculationFeedbackRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>(message.getMetadata());
        Map<String, Object> feedback = new LinkedHashMap<>();
        if (metadata.get(FEEDBACK_KEY) instanceof Map<?, ?> previous) {
            previous.forEach((key, value) -> feedback.put(String.valueOf(key), value));
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("verdict", request.verdict().name());
        entry.put("reason", request.reason() == null ? null : request.reason().name());
        entry.put("at", Instant.now(clock).truncatedTo(ChronoUnit.MILLIS).toString());
        feedback.put(request.itemId(), entry);
        metadata.put(FEEDBACK_KEY, feedback);
        message.setMetadata(metadata);
        messageRepository.save(message);
    }
}
