package com.unisage.backend.audit;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.temporal.Temporal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.entity.AuditLog;
import com.unisage.backend.entity.enums.AuditAction;
import com.unisage.backend.entity.enums.ResourceType;
import com.unisage.backend.security.UserPrincipal;

/**
 * Central, ORM-level capture of every insert/update/delete against a mapped entity — this is
 * what makes "mọi thao tác đụng tới DB đều phải được ghi lại" true regardless of which service
 * or code path triggered the write (including cascades), instead of relying on every
 * *ServiceImpl* to remember an explicit audit call. See docs/adr/0003-audit-log-persistence.md.
 *
 * Registered globally via {@link AuditHibernateIntegrator} — NOT a Spring bean, so it cannot use
 * constructor/field injection; it reaches Spring beans through {@link SpringContextHolder}.
 */
public class AuditEventListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private static final long serialVersionUID = 1L;

    // The audit log itself must never be audited (would recurse forever via AuditLogWriter.save).
    // Message/Conversation are excluded too: they're high-volume chat content (every AI reply is
    // a CREATE-then-UPDATE pair as streaming completes), and Message.content is free-form
    // user/assistant text - capturing full chat content verbatim into an admin-visible,
    // immutable audit table is a privacy exposure this trail was never meant to create, not
    // just noise. See docs/adr/0003-audit-log-persistence.md.
    // UsageLimit is the per-identity token counter, rewritten on every chat turn: pure bookkeeping.
    // SystemHealthCheck is written by the health-check scheduler every few minutes with no human
    // actor - it flooded the trail with "Hệ thống / Khác" rows (UNISAGE-92).
    private static final Set<String> EXCLUDED_ENTITIES =
            Set.of("AuditLog", "Message", "Conversation", "UsageLimit", "SystemHealthCheck");

    // Field AuthServiceImpl.login() touches on every login (updatedAt/updatedBy are already
    // dropped as BOOKKEEPING_FIELDS) - see the skip check in handle() below.
    private static final Set<String> LOGIN_BOOKKEEPING_FIELDS = Set.of("lastLogin");

    // Never leak secrets into the audit trail.
    private static final Set<String> SENSITIVE_FIELDS = Set.of("passwordHash", "apiKeyEncrypted");

    // BaseEntity/optimistic-lock bookkeeping that changes on every write - already covered by the
    // audit row's own actor/createdAt, so listing it as a "change" is pure noise (UNISAGE-92).
    private static final Set<String> BOOKKEEPING_FIELDS =
            Set.of("createdAt", "createdBy", "updatedAt", "updatedBy", "version");

    private static final String SOFT_DELETE_FIELD = "deletedAt";

    // Human-readable name of the row, checked in this order. Snapshotted at write time as
    // "_label" because the row may later be renamed or deleted, so CREATE/DELETE entries can say
    // WHICH object without dumping every field of it (UNISAGE-92).
    private static final List<String> LABEL_FIELDS =
            List.of("title", "name", "label", "code", "llmModelName", "configKey", "email");

    private static final Map<String, ResourceType> RESOURCE_TYPE_BY_ENTITY = Map.ofEntries(
            Map.entry("User", ResourceType.USER),
            Map.entry("Role", ResourceType.ROLE),
            Map.entry("Permission", ResourceType.PERMISSION),
            Map.entry("Department", ResourceType.DEPARTMENT),
            Map.entry("UserDepartmentAccess", ResourceType.USER_DEPARTMENT_ACCESS),
            Map.entry("Document", ResourceType.DOCUMENT),
            Map.entry("DocumentVersion", ResourceType.DOCUMENT),
            Map.entry("Category", ResourceType.CATEGORY),
            Map.entry("AccessLevel", ResourceType.ACCESS_LEVEL),
            Map.entry("ChatModel", ResourceType.CHAT_MODEL),
            Map.entry("Conversation", ResourceType.CONVERSATION),
            Map.entry("Message", ResourceType.MESSAGE),
            Map.entry("Ticket", ResourceType.TICKET),
            Map.entry("SystemConfig", ResourceType.SYSTEM_CONFIG),
            Map.entry("UsageLimitPlan", ResourceType.USAGE_LIMIT_PLAN));

    @Override
    public void onPostInsert(PostInsertEvent event) {
        handle(event.getEntity(), event.getId(), AuditAction.CREATE,
                event.getPersister(), null, event.getState());
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        handle(event.getEntity(), event.getId(), AuditAction.UPDATE,
                event.getPersister(), event.getOldState(), event.getState());
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        handle(event.getEntity(), event.getId(), AuditAction.DELETE,
                event.getPersister(), event.getDeletedState(), null);
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        // We do our own commit-ordering via TransactionSynchronization (see enqueue()) instead
        // of Hibernate's post-commit listener variant, so plain post-flush firing is enough.
        return false;
    }

    private void handle(Object entity, Object id, AuditAction action, EntityPersister persister,
                         Object[] oldState, Object[] newState) {
        String entityName = entity.getClass().getSimpleName();
        // Hibernate proxies/bytebuddy-enhanced subclasses append a suffix; strip it defensively.
        int marker = entityName.indexOf("$");
        if (marker > 0) {
            entityName = entityName.substring(0, marker);
        }
        if (EXCLUDED_ENTITIES.contains(entityName)) {
            return;
        }

        String[] propertyNames = persister.getPropertyNames();
        if (action == AuditAction.UPDATE && isSoftDelete(propertyNames, oldState, newState)) {
            // Soft delete is an UPDATE at the ORM level, but to an admin it is a deletion.
            action = AuditAction.DELETE;
        }

        Map<String, Object> details;
        if (action == AuditAction.UPDATE) {
            details = buildChanges(propertyNames, oldState, newState);
            if (details.isEmpty()) {
                // No business field changed (e.g. a version/updatedAt-only touch).
                return;
            }
            if ("User".equals(entityName) && LOGIN_BOOKKEEPING_FIELDS.containsAll(details.keySet())) {
                // AuthServiceImpl.login() already publishes a LOGIN domain event
                // (AuthAuditListener) for this exact moment - this UPDATE fires from the same
                // request writing user.lastLogin, and would otherwise show up as a second,
                // redundant row for the same login. Only skip when lastLogin is the ONLY changed
                // field, so a real profile edit made in the same transaction is still captured.
                return;
            }
        } else {
            // CREATE/DELETE act on the object as a whole - record which one, not every field.
            details = new LinkedHashMap<>();
        }
        // Every row carries the object's name so the admin list can say WHICH object instead of
        // a bare UUID.
        String label = labelOf(propertyNames, newState != null ? newState : oldState);
        if (label != null) {
            details.put("_label", label);
        }

        ResourceType resourceType = RESOURCE_TYPE_BY_ENTITY.getOrDefault(entityName, ResourceType.OTHER);
        details.put("_entity", entityName);

        UUID actorId = null;
        String actorCode = null;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal) {
            actorId = principal.getUserId();
            actorCode = principal.getCode();
        }

        AuditLog log = AuditLog.builder()
                .actorId(actorId)
                .actorCode(actorCode)
                .action(action)
                .resourceType(resourceType)
                .resourceId(id != null ? String.valueOf(id) : null)
                .details(toJson(details))
                .createdAt(LocalDateTime.now())
                .build();

        enqueue(log);
    }

    private void enqueue(AuditLog log) {
        AuditLogWriter writer = SpringContextHolder.getBean(AuditLogWriter.class);
        if (writer == null) {
            return; // Spring context not up yet — nothing sane to do.
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Defer the write to just after the business transaction commits, in its own
            // transaction (AuditLogWriter#persist is REQUIRES_NEW) — an audit row must never be
            // visible for a change that ultimately rolled back, and must never itself cause the
            // business transaction to fail.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writer.persist(log);
                }
            });
        } else {
            writer.persist(log);
        }
    }

    private Map<String, Object> buildChanges(String[] propertyNames, Object[] oldState, Object[] newState) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (propertyNames == null) {
            return details;
        }
        for (int i = 0; i < propertyNames.length; i++) {
            String property = propertyNames[i];
            if (SENSITIVE_FIELDS.contains(property) || BOOKKEEPING_FIELDS.contains(property)) {
                continue;
            }
            Object oldValue = valueAt(oldState, i);
            Object newValue = valueAt(newState, i);
            if (!Objects.equals(oldValue, newValue)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("old", oldValue);
                change.put("new", newValue);
                details.put(property, change);
            }
        }
        return details;
    }

    private boolean isSoftDelete(String[] propertyNames, Object[] oldState, Object[] newState) {
        int index = indexOf(propertyNames, SOFT_DELETE_FIELD);
        return index >= 0 && valueAt(oldState, index) == null && valueAt(newState, index) != null;
    }

    private String labelOf(String[] propertyNames, Object[] state) {
        for (String field : LABEL_FIELDS) {
            Object value = valueAt(state, indexOf(propertyNames, field));
            if (value instanceof CharSequence text && !text.toString().isBlank()) {
                return text.toString();
            }
        }
        return null;
    }

    private int indexOf(String[] propertyNames, String property) {
        if (propertyNames != null) {
            for (int i = 0; i < propertyNames.length; i++) {
                if (property.equals(propertyNames[i])) {
                    return i;
                }
            }
        }
        return -1;
    }

    private Object valueAt(Object[] state, int index) {
        return state != null && index >= 0 && index < state.length ? safeValue(state[index]) : null;
    }

    private Object safeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Temporal || value instanceof Date || value instanceof UUID) {
            return value;
        }
        // Association values (e.g. a ManyToOne User, possibly an uninitialized proxy) — record
        // just their id, never serialize/initialize the whole associated entity graph.
        try {
            Method getId = value.getClass().getMethod("getId");
            return getId.invoke(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String toJson(Map<String, Object> details) {
        ObjectMapper mapper = SpringContextHolder.getBean(ObjectMapper.class);
        if (mapper == null) {
            return String.valueOf(details);
        }
        try {
            return mapper.writeValueAsString(details);
        } catch (Exception e) {
            return String.valueOf(details);
        }
    }
}
