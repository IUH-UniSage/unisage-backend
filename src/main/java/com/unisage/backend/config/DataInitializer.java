package com.unisage.backend.config;

import com.unisage.backend.entity.*;
import com.unisage.backend.entity.enums.PermissionMethod;
import com.unisage.backend.entity.enums.ResourceType;
import com.unisage.backend.predefined.PredefinedPermissions;
import com.unisage.backend.predefined.PredefinedRoles;
import com.unisage.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository            roleRepository;
    private final PermissionRepository      permissionRepository;
    private final RolePermissionRepository  rolePermissionRepository;
    private final UserRepository            userRepository;
    private final DepartmentRepository      departmentRepository;
    private final AccessLevelRepository     accessLevelRepository;
    private final PasswordEncoder           passwordEncoder;

    @Value("${DEFAULT_SUPERADMIN_PASS:Admin@123456}")
    private String defaultSuperAdminPassword;

    @Value("${DEFAULT_INGESTADMIN_PASS:Ingest@123456}")
    private String defaultIngestAdminPassword;

    @Value("${DEFAULT_USER_PASS:User@123456}")
    private String defaultUserPassword;

    // ─────────────────────────────────────────────────────────────────────
    @Override
    @Transactional
    public void run(String... args) {
        // Runs independently of the RBAC skip-guard below, so re-running on an already
        // initialised system still fills in any department still missing.
        seedDepartments();
        Map<Integer, AccessLevel> accessLevels = seedAccessLevels();

        if (roleRepository.findByName(PredefinedRoles.SUPER_ADMIN).isPresent()) {
            log.info(">>> System already initialised — skipping DataInitializer.");
            return;
        }

        log.info(">>> Initialising RBAC data for the first time...");

        // 1. Seed every permission
        Map<String, Permission> perms = seedPermissions();

        // 2. Seed roles
        Role superAdmin  = seedRole(PredefinedRoles.SUPER_ADMIN,  "Quyền quản trị tối cao của hệ thống");
        Role ingestAdmin = seedRole(PredefinedRoles.INGEST_ADMIN, "Quản trị nạp liệu và tài liệu");
        Role userRole    = seedRole(PredefinedRoles.USER,          "Người dùng cuối");

        // 3. Assign permissions per role
        assignSuperAdmin(superAdmin, perms);     // SUPER_ADMIN gets all _ALL permissions
        assignIngestAdmin(ingestAdmin, perms);
        assignUser(userRole, perms);

        // 4. Seed one default user per role
        seedUser("admin@unisage.com",    "SA-001", "System",   "Administrator", "0999999999", superAdmin,  defaultSuperAdminPassword, accessLevels.get(5));
        seedUser("ingest@unisage.com",   "IA-001", "Ingest",   "Admin",         "0888888888", ingestAdmin, defaultIngestAdminPassword, accessLevels.get(5));
        seedUser("user@unisage.com",     "US-001", "Default",  "User",          "0777777777", userRole,    defaultUserPassword, accessLevels.get(0));

        log.info(">>> [SUCCESS] RBAC initialisation complete.");
    }

    // ─── Permission seeding ──────────────────────────────────────────────
    /**
     * Map key = "NAME:LEVEL" (e.g. "DOCUMENT_ALL:3", "USER_READ:null").
     * This allows multiple rows with the same name but different access levels.
     */
    private Map<String, Permission> seedPermissions() {
        List<PermDef> defs = buildPermissionDefinitions();
        Map<String, Permission> map = new LinkedHashMap<>();
        for (PermDef d : defs) {
            String key = permKey(d.name(), d.accessLevel());
            if (!permissionRepository.existsByNameAndAccessLevel(d.name(), d.accessLevel())) {
                Permission p = Permission.builder()
                        .name(d.name())
                        .path(d.path())
                        .method(d.method())
                        .resourceType(d.resource())
                        .description(d.description())
                        .accessLevel(d.accessLevel())
                        .isActive(true)
                        .build();
                permissionRepository.save(p);
                map.put(key, p);
                log.debug("  Permission seeded: {}", key);
            } else {
                permissionRepository.findByNameAndAccessLevel(d.name(), d.accessLevel())
                        .ifPresent(p -> map.put(key, p));
            }
        }
        log.info("  {} permissions ready.", map.size());
        return map;
    }

    /** Composite map key: name + ":" + (level == null ? "null" : level). */
    private static String permKey(String name, Integer level) {
        return name + ":" + (level == null ? "null" : level);
    }

    /**
     * Central definition of every permission.
     * Pattern: (name, antPath, method, resourceType, description)
     *
     * Each resource group contains:
     *   - <RESOURCE>_ALL  → ALL method on the root path (wildcard access to the whole resource)
     *   - Individual CRUD / action permissions
     */
    private List<PermDef> buildPermissionDefinitions() {
        List<PermDef> list = new ArrayList<>(List.of(

            // ── SUPER_ADMIN wildcard ──────────────────────────────────────
            def(PredefinedPermissions.SUPER_ADMIN_ALL,
                "/**", PermissionMethod.ALL, ResourceType.SYSTEM,
                "Toàn quyền hệ thống", null),

            // ── User ─────────────────────────────────────────────────────
            def(PredefinedPermissions.USER_ALL,
                "/users/**", PermissionMethod.ALL, ResourceType.USER,
                "Toàn quyền người dùng", null),
            def(PredefinedPermissions.USER_READ,
                "/users/**", PermissionMethod.GET, ResourceType.USER,
                "Xem hồ sơ người dùng", null),
            def(PredefinedPermissions.USER_CREATE,
                "/users", PermissionMethod.POST, ResourceType.USER,
                "Tạo người dùng mới", null),
            def(PredefinedPermissions.USER_UPDATE,
                "/users/**", PermissionMethod.PUT, ResourceType.USER,
                "Cập nhật hồ sơ người dùng", null),
            def(PredefinedPermissions.USER_DELETE,
                "/users/**", PermissionMethod.DELETE, ResourceType.USER,
                "Xóa người dùng", null),

            // ── Role ─────────────────────────────────────────────────────
            def(PredefinedPermissions.ROLE_ALL,
                "/rbac/roles/**", PermissionMethod.ALL, ResourceType.ROLE,
                "Toàn quyền vai trò", null),
            def(PredefinedPermissions.ROLE_READ,
                "/rbac/roles/**", PermissionMethod.GET, ResourceType.ROLE,
                "Xem vai trò", null),
            def(PredefinedPermissions.ROLE_CREATE,
                "/rbac/roles", PermissionMethod.POST, ResourceType.ROLE,
                "Tạo vai trò", null),
            def(PredefinedPermissions.ROLE_UPDATE,
                "/rbac/roles/**", PermissionMethod.PUT, ResourceType.ROLE,
                "Cập nhật vai trò", null),
            def(PredefinedPermissions.ROLE_DELETE,
                "/rbac/roles/**", PermissionMethod.DELETE, ResourceType.ROLE,
                "Xóa vai trò", null),

            // ── Permission ───────────────────────────────────────────────
            def(PredefinedPermissions.PERMISSION_ALL,
                "/rbac/permissions/**", PermissionMethod.ALL, ResourceType.PERMISSION,
                "Toàn quyền permission", null),
            def(PredefinedPermissions.PERMISSION_READ,
                "/rbac/permissions/**", PermissionMethod.GET, ResourceType.PERMISSION,
                "Xem danh sách quyền", null),
            def(PredefinedPermissions.PERMISSION_CREATE,
                "/rbac/permissions", PermissionMethod.POST, ResourceType.PERMISSION,
                "Tạo quyền mới", null),
            def(PredefinedPermissions.PERMISSION_UPDATE,
                "/rbac/permissions/**", PermissionMethod.PUT, ResourceType.PERMISSION,
                "Cập nhật quyền", null),
            def(PredefinedPermissions.PERMISSION_DELETE,
                "/rbac/permissions/**", PermissionMethod.DELETE, ResourceType.PERMISSION,
                "Xóa quyền", null),

            // ── Category ─────────────────────────────────────────────────
            def(PredefinedPermissions.CATEGORY_ALL,
                "/categories/**", PermissionMethod.ALL, ResourceType.CATEGORY,
                "Toàn quyền danh mục", null),
            def(PredefinedPermissions.CATEGORY_READ,
                "/categories/**", PermissionMethod.GET, ResourceType.CATEGORY,
                "Xem danh mục", null),
            def(PredefinedPermissions.CATEGORY_CREATE,
                "/categories", PermissionMethod.POST, ResourceType.CATEGORY,
                "Tạo danh mục", null),
            def(PredefinedPermissions.CATEGORY_UPDATE,
                "/categories/**", PermissionMethod.PUT, ResourceType.CATEGORY,
                "Cập nhật danh mục", null),
            def(PredefinedPermissions.CATEGORY_DELETE,
                "/categories/**", PermissionMethod.DELETE, ResourceType.CATEGORY,
                "Xóa danh mục", null),

            // ── Department ───────────────────────────────────────────────
            def(PredefinedPermissions.DEPARTMENT_ALL,
                "/departments/**", PermissionMethod.ALL, ResourceType.DEPARTMENT,
                "Toàn quyền phòng ban", null),
            def(PredefinedPermissions.DEPARTMENT_READ,
                "/departments/**", PermissionMethod.GET, ResourceType.DEPARTMENT,
                "Xem phòng ban", null),
            def(PredefinedPermissions.DEPARTMENT_CREATE,
                "/departments", PermissionMethod.POST, ResourceType.DEPARTMENT,
                "Tạo phòng ban", null),
            def(PredefinedPermissions.DEPARTMENT_UPDATE,
                "/departments/**", PermissionMethod.PUT, ResourceType.DEPARTMENT,
                "Cập nhật phòng ban", null),
            def(PredefinedPermissions.DEPARTMENT_DELETE,
                "/departments/**", PermissionMethod.DELETE, ResourceType.DEPARTMENT,
                "Xóa phòng ban", null),

            // ── AccessLevel ──────────────────────────────────────────────
            def(PredefinedPermissions.ACCESS_LEVEL_ALL,
                "/access-levels/**", PermissionMethod.ALL, ResourceType.ACCESS_LEVEL,
                "Toàn quyền cấp độ truy cập", null),
            def(PredefinedPermissions.ACCESS_LEVEL_READ,
                "/access-levels/**", PermissionMethod.GET, ResourceType.ACCESS_LEVEL,
                "Xem cấp độ truy cập", null),
            def(PredefinedPermissions.ACCESS_LEVEL_CREATE,
                "/access-levels", PermissionMethod.POST, ResourceType.ACCESS_LEVEL,
                "Tạo cấp độ truy cập", null),
            def(PredefinedPermissions.ACCESS_LEVEL_UPDATE,
                "/access-levels/**", PermissionMethod.PUT, ResourceType.ACCESS_LEVEL,
                "Cập nhật cấp độ truy cập", null),
            def(PredefinedPermissions.ACCESS_LEVEL_DELETE,
                "/access-levels/**", PermissionMethod.DELETE, ResourceType.ACCESS_LEVEL,
                "Xóa cấp độ truy cập", null)

            // ── Document (CREATE = ingest) ────────────────────────────────

        ));

        // DOCUMENT WITH ACCESS LEVELS (L1-L5)
        for (int i = 1; i <= 5; i++) {
            list.add(def(PredefinedPermissions.DOCUMENT_ALL , "/documents/**", PermissionMethod.ALL, ResourceType.DOCUMENT, "Toàn quyền tài liệu L" + i, i));
            list.add(def(PredefinedPermissions.DOCUMENT_READ , "/documents/**", PermissionMethod.GET, ResourceType.DOCUMENT, "Xem tài liệu L" + i, i));
            list.add(def(PredefinedPermissions.DOCUMENT_CREATE , "/documents/**", PermissionMethod.POST, ResourceType.DOCUMENT, "Tạo tài liệu L" + i, i));
            list.add(def(PredefinedPermissions.DOCUMENT_UPDATE , "/documents/**", PermissionMethod.PUT, ResourceType.DOCUMENT, "Cập nhật tài liệu L" + i, i));
            list.add(def(PredefinedPermissions.DOCUMENT_DELETE , "/documents/**", PermissionMethod.DELETE, ResourceType.DOCUMENT, "Xóa tài liệu L" + i, i));
        }

        list.addAll(List.of(

            // ── ChatModel ────────────────────────────────────────────────
            def(PredefinedPermissions.CHAT_MODEL_ALL,
                "/chat-models/**", PermissionMethod.ALL, ResourceType.CHAT_MODEL,
                "Toàn quyền mô hình chat", null),
            def(PredefinedPermissions.CHAT_MODEL_READ,
                "/chat-models/**", PermissionMethod.GET, ResourceType.CHAT_MODEL,
                "Xem mô hình chat", null),
            def(PredefinedPermissions.CHAT_MODEL_CREATE,
                "/chat-models", PermissionMethod.POST, ResourceType.CHAT_MODEL,
                "Tạo mô hình chat", null),
            def(PredefinedPermissions.CHAT_MODEL_UPDATE,
                "/chat-models/**", PermissionMethod.PUT, ResourceType.CHAT_MODEL,
                "Cập nhật mô hình chat", null),
            def(PredefinedPermissions.CHAT_MODEL_DELETE,
                "/chat-models/**", PermissionMethod.DELETE, ResourceType.CHAT_MODEL,
                "Xóa mô hình chat", null),

            // ── Conversation ─────────────────────────────────────────────
            def(PredefinedPermissions.CONVERSATION_ALL,
                "/conversations/**", PermissionMethod.ALL, ResourceType.CONVERSATION,
                "Toàn quyền hội thoại", null),
            def(PredefinedPermissions.CONVERSATION_READ,
                "/conversations/**", PermissionMethod.GET, ResourceType.CONVERSATION,
                "Xem hội thoại", null),
            def(PredefinedPermissions.CONVERSATION_CREATE,
                "/conversations", PermissionMethod.POST, ResourceType.CONVERSATION,
                "Tạo hội thoại mới", null),
            def(PredefinedPermissions.CONVERSATION_DELETE,
                "/conversations/**", PermissionMethod.DELETE, ResourceType.CONVERSATION,
                "Xóa hội thoại", null),

            // ── Message ──────────────────────────────────────────────────
            def(PredefinedPermissions.MESSAGE_ALL,
                "/messages/**", PermissionMethod.ALL, ResourceType.MESSAGE,
                "Toàn quyền tin nhắn", null),
            def(PredefinedPermissions.MESSAGE_READ,
                "/messages/**", PermissionMethod.GET, ResourceType.MESSAGE,
                "Xem tin nhắn", null),
            def(PredefinedPermissions.MESSAGE_SEND,
                "/messages", PermissionMethod.POST, ResourceType.MESSAGE,
                "Gửi tin nhắn", null),

            // ── AuditLog ─────────────────────────────────────────────────
            def(PredefinedPermissions.AUDIT_LOG_ALL,
                "/audit-logs/**", PermissionMethod.ALL, ResourceType.AUDIT_LOG,
                "Toàn quyền nhật ký hệ thống", null),
            def(PredefinedPermissions.AUDIT_LOG_READ,
                "/audit-logs/**", PermissionMethod.GET, ResourceType.AUDIT_LOG,
                "Xem nhật ký hệ thống", null),

            // ── LlmTraceLog ──────────────────────────────────────────────
            def(PredefinedPermissions.LLM_TRACE_LOG_ALL,
                "/llm-trace-logs/**", PermissionMethod.ALL, ResourceType.LLM_TRACE_LOG,
                "Toàn quyền nhật ký LLM", null),
            def(PredefinedPermissions.LLM_TRACE_LOG_READ,
                "/llm-trace-logs/**", PermissionMethod.GET, ResourceType.LLM_TRACE_LOG,
                "Xem nhật ký LLM", null)
        ));

        // INGEST WITH ACCESS LEVELS (L1-L5)
        for (int i = 1; i <= 5; i++) {
            list.add(def(PredefinedPermissions.INGEST_ALL, "/ai/ingest/**", PermissionMethod.ALL, ResourceType.INGEST, "Toàn quyền nạp liệu L" + i, i));
        }

        return list;
    }

    // ─── Role helpers ────────────────────────────────────────────────────
    private Role seedRole(String name, String description) {
        return roleRepository.findByName(name).orElseGet(() -> {
            Role r = Role.builder()
                    .name(name)
                    .description(description)
                    .isSystemRole(true)
                    .isActive(true)
                    .build();
            roleRepository.save(r);
            log.info("  Role seeded: {}", name);
            return r;
        });
    }

    /** SUPER_ADMIN gets _ALL permissions, plus DOCUMENT_ALL at level 5. */
    private void assignSuperAdmin(Role role, Map<String, Permission> perms) {
        int count = 0;
        for (Permission p : perms.values()) {
            if (p.getName().endsWith("_ALL")) {
                if (p.getAccessLevel() == null || (p.getName().equals(PredefinedPermissions.DOCUMENT_ALL) && p.getAccessLevel() == 5)) {
                    assignPermission(role, p);
                    count++;
                }
            }
        }
        log.info("  {} _ALL permissions assigned to role '{}'.", count, role.getName());
    }

    /**
     * INGEST_ADMIN — manages knowledge base, documents, chat models.
     * Does NOT have user/role/permission management.
     */
    private void assignIngestAdmin(Role role, Map<String, Permission> perms) {
        List<String> keys = List.of(
            permKey(PredefinedPermissions.DEPARTMENT_READ, null),
            permKey(PredefinedPermissions.DOCUMENT_ALL, 5),
            permKey(PredefinedPermissions.CATEGORY_ALL, null),
            permKey(PredefinedPermissions.CHAT_MODEL_READ, null),
            permKey(PredefinedPermissions.LLM_TRACE_LOG_READ, null),
            permKey(PredefinedPermissions.INGEST_ALL, 5)
        );
        assign(role, perms, keys);
        log.info("  {} permissions assigned to role '{}'.", keys.size(), role.getName());
    }

    /**
     * USER — end-user chat access.
     * Can start conversations, send messages, read their own data.
     */
    private void assignUser(Role role, Map<String, Permission> perms) {
        List<String> keys = List.of(
            permKey(PredefinedPermissions.CONVERSATION_ALL, null),
            permKey(PredefinedPermissions.MESSAGE_READ, null),
            permKey(PredefinedPermissions.MESSAGE_SEND, null),
            permKey(PredefinedPermissions.DOCUMENT_READ, 5),
            permKey(PredefinedPermissions.DEPARTMENT_READ, null),
            permKey(PredefinedPermissions.CATEGORY_READ, null),
            permKey(PredefinedPermissions.CHAT_MODEL_READ, null),
            permKey(PredefinedPermissions.USER_READ, null),
            permKey(PredefinedPermissions.USER_UPDATE, null)
        );
        assign(role, perms, keys);
        log.info("  {} permissions assigned to role '{}'.", keys.size(), role.getName());
    }

    // ─── Assignment helpers ──────────────────────────────────────────────
    private void assign(Role role, Map<String, Permission> perms, List<String> keys) {
        for (String k : keys) {
            Permission p = perms.get(k);
            if (p != null) {
                assignPermission(role, p);
            } else {
                log.warn("  Permission key '{}' not found in seeded permissions — skipping.", k);
            }
        }
    }

    private void assignPermission(Role role, Permission permission) {
        if (!rolePermissionRepository.existsByRoleAndPermission(role, permission)) {
            rolePermissionRepository.save(
                    RolePermission.builder()
                            .role(role)
                            .permission(permission)
                            .build()
            );
        }
    }

    // ─── User bootstrap ──────────────────────────────────────────────────
    /**
     * Creates one User (login identity + profile merged) for the given role
     * if the email is not yet taken.
     */
    private void seedUser(String email, String code,
                          String firstName, String lastName, String phone,
                          Role role, String rawPassword, AccessLevel accessLevel) {
        if (userRepository.findByEmail(email).isPresent()) return;

        User user = User.builder()
                .email(email)
                .code(code)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .firstName(firstName)
                .lastName(lastName)
                .role(role)
                .accessLevel(accessLevel)
                .phone(phone)
                .gender("NAM")
                .isActive(true)
                .build();
        userRepository.save(user);

        log.info("  User seeded: {} [{}]", email, role.getName());
    }

    // ─── AccessLevel seeding ───────────────────────────────────────────────
    /** Seeds levels 0 (no restriction) through 5, matching the DOCUMENT and INGEST permission levels. */
    private Map<Integer, AccessLevel> seedAccessLevels() {
        Map<Integer, AccessLevel> map = new LinkedHashMap<>();
        Map<Integer, String> descriptions = Map.of(
                0, "Không giới hạn — tài liệu công khai nội bộ",
                1, "Cấp độ 1",
                2, "Cấp độ 2",
                3, "Cấp độ 3",
                4, "Cấp độ 4",
                5, "Cấp độ 5 — tối cao"
        );
        for (int level = 0; level <= 5; level++) {
            int finalLevel = level;
            AccessLevel accessLevel = accessLevelRepository.findByLevel(level).orElseGet(() -> {
                AccessLevel a = AccessLevel.builder()
                        .level(finalLevel)
                        .description(descriptions.get(finalLevel))
                        .build();
                accessLevelRepository.save(a);
                log.info("  AccessLevel seeded: L{}", finalLevel);
                return a;
            });
            map.put(level, accessLevel);
        }
        return map;
    }

    // ─── Department seeding ────────────────────────────────────────────────
    private void seedDepartments() {
        seedDepartment("PHONG_DAO_TAO", null, "Phòng Đào Tạo");
        seedDepartment("PHONG_CTSV", null, "Phòng Công Tác Sinh Viên");
        seedDepartment("PHONG_TC_KT", null, "Phòng Tài Chính - Kế Toán");

        Department khoa = seedDepartment("Khoa", null, null);

        Department khoaCntt = seedDepartment("KHOA_CNTT", khoa, "Khoa Công Nghệ Thông Tin");
        seedDepartment("BM_CONG_NGHE_PHAN_MEM", khoaCntt, "Bộ môn Công Nghệ Phần Mềm");
        seedDepartment("BM_TRI_TUE_NHAN_TAI", khoaCntt, "Bộ môn Trí Tuệ Nhân Tạo");
        seedDepartment("BM_MANG_VIEN_THONG", khoaCntt, "Bộ môn Mạng Viễn Thông");

        Department khoaKinhTe = seedDepartment("KHOA_KINH_TE", khoa, "Khoa Kinh Tế");
        seedDepartment("BM_KE_TOAN", khoaKinhTe, "Bộ môn Kế Toán");
        seedDepartment("BM_QUAN_TRI_KINH_DOANH", khoaKinhTe, "Bộ môn Quản Trị Kinh Doanh");
        seedDepartment("BM_TAI_CHINH_NGAN_HANG", khoaKinhTe, "Bộ môn Tài Chính Ngân Hàng");

        seedDepartment("KHOA_LY_LUAN_CHINH_TRI", khoa, "Khoa Lý Luận Chính Trị");

        log.info("  Departments ready.");
    }

    private Department seedDepartment(String name, Department parent, String description) {
        return departmentRepository.findByName(name).orElseGet(() -> {
            Department d = Department.builder()
                    .parent(parent)
                    .name(name)
                    .description(description)
                    .build();
            departmentRepository.save(d);
            log.info("  Department seeded: {}", name);
            return d;
        });
    }

    // ─── Compact builder record ──────────────────────────────────────────
    private record PermDef(String name, String path, PermissionMethod method,
                           ResourceType resource, String description, Integer accessLevel) {}

    private static PermDef def(String name, String path, PermissionMethod method,
                               ResourceType resource, String description, Integer accessLevel) {
        return new PermDef(name, path, method, resource, description, accessLevel);
    }
}
