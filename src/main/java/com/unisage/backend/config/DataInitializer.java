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
    private final UserDepartmentAccessRepository userDepartmentAccessRepository;
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
        Role superAdmin  = seedRole(PredefinedRoles.SUPER_ADMIN,  "Quyền quản trị tối cao của hệ thống", true);
        Role ingestAdmin = seedRole(PredefinedRoles.INGEST_ADMIN, "Quản trị nạp liệu và tài liệu", true);
        // The end-user role is not a system role: the frontend uses this flag to decide who sees the
        // admin-workspace link, and a plain USER must not.
        Role userRole    = seedRole(PredefinedRoles.USER,          "Người dùng cuối", false);

        // 3. Assign permissions per role
        assignSuperAdmin(superAdmin, perms);     // SUPER_ADMIN gets all _ALL permissions
        assignIngestAdmin(ingestAdmin, perms);
        assignUser(userRole, perms);

        // 4. Seed one default user per role
        User superAdminUser = seedUser("admin@unisage.com",    "SA-001", "System",   "Administrator", "0999999999", superAdmin,  defaultSuperAdminPassword, accessLevels.get(5));
        User ingestAdminUser = seedUser("ingest@unisage.com",   "IA-001", "Ingest",   "Admin",         "0888888888", ingestAdmin, defaultIngestAdminPassword, accessLevels.get(5));
        User ingestAdminDaoTaoUser = seedUser("ingest.daotao@unisage.com", "IA-002", "Ingest", "Admin (Phong Dao Tao)", "0888888887", ingestAdmin, defaultIngestAdminPassword, null);
        seedUser("user@unisage.com",     "US-001", "Default",  "User",          "0777777777", userRole,    defaultUserPassword, accessLevels.get(0));

        // 5. SUPER_ADMIN quan tri moi phong ban - user_department_access khong cascade nen can 1 row
        //    rieng cho tung department (ke ca node cha lan con), o access_level toi da.
        assignAllDepartmentsToUser(superAdminUser, accessLevels.get(5));

        // IA-001 is the general-purpose ingest admin (as opposed to IA-002 below, a narrow
        // department-scoped fixture for permission-gate tests) - without this it has zero
        // user_department_access rows and the ingestion wizard rejects every document
        // ("Bạn không có quyền truy cập phòng ban ...") regardless of ACCESS_LEVEL_READ/document
        // fields being set correctly.
        assignAllDepartmentsToUser(ingestAdminUser, accessLevels.get(5));

        // 6. Mock ingestAdmin thu 2 - chi co quyen department_access o PHONG_DAO_TAO, level 3 - dung de
        //    test permission gate/department scoping ma khong bi lan voi ingestAdmin toan quyen o buoc 4.
        assignDepartmentToUser(ingestAdminDaoTaoUser, "PHONG_DAO_TAO", accessLevels.get(3));

        log.info(">>> [SUCCESS] RBAC initialisation complete.");
    }

    // ─── Permission seeding ──────────────────────────────────────────────
    private Map<String, Permission> seedPermissions() {
        List<PermDef> defs = buildPermissionDefinitions();
        Map<String, Permission> map = new LinkedHashMap<>();
        for (PermDef d : defs) {
            String key = permKey(d.name());
            if (!permissionRepository.existsByName(d.name())) {
                Permission p = Permission.builder()
                        .name(d.name())
                        .path(d.path())
                        .method(d.method())
                        .resourceType(d.resource())
                        .description(d.description())
                        .isActive(true)
                        .build();
                permissionRepository.save(p);
                map.put(key, p);
                log.debug("  Permission seeded: {}", key);
            } else {
                permissionRepository.findByName(d.name())
                        .ifPresent(p -> map.put(key, p));
            }
        }
        log.info("  {} permissions ready.", map.size());
        return map;
    }

    private static String permKey(String name) {
        return name;
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
                "Toàn quyền hệ thống"),

            // ── User ─────────────────────────────────────────────────────
            def(PredefinedPermissions.USER_ALL,
                "/users/**", PermissionMethod.ALL, ResourceType.USER,
                "Toàn quyền người dùng"),
            def(PredefinedPermissions.USER_READ,
                "/users/**", PermissionMethod.GET, ResourceType.USER,
                "Xem hồ sơ người dùng"),
            def(PredefinedPermissions.USER_CREATE,
                "/users", PermissionMethod.POST, ResourceType.USER,
                "Tạo người dùng mới"),
            def(PredefinedPermissions.USER_UPDATE,
                "/users/**", PermissionMethod.PUT, ResourceType.USER,
                "Cập nhật hồ sơ người dùng"),
            def(PredefinedPermissions.USER_DELETE,
                "/users/**", PermissionMethod.DELETE, ResourceType.USER,
                "Xóa người dùng"),

            // ── Role ─────────────────────────────────────────────────────
            def(PredefinedPermissions.ROLE_ALL,
                "/rbac/roles/**", PermissionMethod.ALL, ResourceType.ROLE,
                "Toàn quyền vai trò"),
            def(PredefinedPermissions.ROLE_READ,
                "/rbac/roles/**", PermissionMethod.GET, ResourceType.ROLE,
                "Xem vai trò"),
            def(PredefinedPermissions.ROLE_CREATE,
                "/rbac/roles", PermissionMethod.POST, ResourceType.ROLE,
                "Tạo vai trò"),
            def(PredefinedPermissions.ROLE_UPDATE,
                "/rbac/roles/**", PermissionMethod.PUT, ResourceType.ROLE,
                "Cập nhật vai trò"),
            def(PredefinedPermissions.ROLE_DELETE,
                "/rbac/roles/**", PermissionMethod.DELETE, ResourceType.ROLE,
                "Xóa vai trò"),

            // ── Permission ───────────────────────────────────────────────
            def(PredefinedPermissions.PERMISSION_ALL,
                "/rbac/permissions/**", PermissionMethod.ALL, ResourceType.PERMISSION,
                "Toàn quyền permission"),
            def(PredefinedPermissions.PERMISSION_READ,
                "/rbac/permissions/**", PermissionMethod.GET, ResourceType.PERMISSION,
                "Xem danh sách quyền"),
            def(PredefinedPermissions.PERMISSION_CREATE,
                "/rbac/permissions", PermissionMethod.POST, ResourceType.PERMISSION,
                "Tạo quyền mới"),
            def(PredefinedPermissions.PERMISSION_UPDATE,
                "/rbac/permissions/**", PermissionMethod.PUT, ResourceType.PERMISSION,
                "Cập nhật quyền"),
            def(PredefinedPermissions.PERMISSION_DELETE,
                "/rbac/permissions/**", PermissionMethod.DELETE, ResourceType.PERMISSION,
                "Xóa quyền"),

            // ── Category ─────────────────────────────────────────────────
            def(PredefinedPermissions.CATEGORY_ALL,
                "/categories/**", PermissionMethod.ALL, ResourceType.CATEGORY,
                "Toàn quyền danh mục"),
            def(PredefinedPermissions.CATEGORY_READ,
                "/categories/**", PermissionMethod.GET, ResourceType.CATEGORY,
                "Xem danh mục"),
            def(PredefinedPermissions.CATEGORY_CREATE,
                "/categories", PermissionMethod.POST, ResourceType.CATEGORY,
                "Tạo danh mục"),
            def(PredefinedPermissions.CATEGORY_UPDATE,
                "/categories/**", PermissionMethod.PUT, ResourceType.CATEGORY,
                "Cập nhật danh mục"),
            def(PredefinedPermissions.CATEGORY_DELETE,
                "/categories/**", PermissionMethod.DELETE, ResourceType.CATEGORY,
                "Xóa danh mục"),

            // ── Department ───────────────────────────────────────────────
            def(PredefinedPermissions.DEPARTMENT_ALL,
                "/departments/**", PermissionMethod.ALL, ResourceType.DEPARTMENT,
                "Toàn quyền phòng ban"),
            def(PredefinedPermissions.DEPARTMENT_READ,
                "/departments/**", PermissionMethod.GET, ResourceType.DEPARTMENT,
                "Xem phòng ban"),
            def(PredefinedPermissions.DEPARTMENT_CREATE,
                "/departments", PermissionMethod.POST, ResourceType.DEPARTMENT,
                "Tạo phòng ban"),
            def(PredefinedPermissions.DEPARTMENT_UPDATE,
                "/departments/**", PermissionMethod.PUT, ResourceType.DEPARTMENT,
                "Cập nhật phòng ban"),
            def(PredefinedPermissions.DEPARTMENT_DELETE,
                "/departments/**", PermissionMethod.DELETE, ResourceType.DEPARTMENT,
                "Xóa phòng ban"),

            // ── AccessLevel ──────────────────────────────────────────────
            def(PredefinedPermissions.ACCESS_LEVEL_ALL,
                "/access-levels/**", PermissionMethod.ALL, ResourceType.ACCESS_LEVEL,
                "Toàn quyền cấp độ truy cập"),
            def(PredefinedPermissions.ACCESS_LEVEL_READ,
                "/access-levels/**", PermissionMethod.GET, ResourceType.ACCESS_LEVEL,
                "Xem cấp độ truy cập"),
            def(PredefinedPermissions.ACCESS_LEVEL_CREATE,
                "/access-levels", PermissionMethod.POST, ResourceType.ACCESS_LEVEL,
                "Tạo cấp độ truy cập"),
            def(PredefinedPermissions.ACCESS_LEVEL_UPDATE,
                "/access-levels/**", PermissionMethod.PUT, ResourceType.ACCESS_LEVEL,
                "Cập nhật cấp độ truy cập"),
            def(PredefinedPermissions.ACCESS_LEVEL_DELETE,
                "/access-levels/**", PermissionMethod.DELETE, ResourceType.ACCESS_LEVEL,
                "Xóa cấp độ truy cập"),

            // ── Ticket ────────────────────────────────────────────────────
            def(PredefinedPermissions.TICKET_ALL,
                "/tickets/**", PermissionMethod.ALL, ResourceType.TICKET,
                "Toàn quyền yêu cầu hỗ trợ"),
            def(PredefinedPermissions.TICKET_READ,
                "/tickets/**", PermissionMethod.GET, ResourceType.TICKET,
                "Xem yêu cầu hỗ trợ"),
            def(PredefinedPermissions.TICKET_CREATE,
                "/tickets", PermissionMethod.POST, ResourceType.TICKET,
                "Tạo yêu cầu hỗ trợ"),
            def(PredefinedPermissions.TICKET_UPDATE,
                "/tickets/**", PermissionMethod.PATCH, ResourceType.TICKET,
                "Cập nhật yêu cầu hỗ trợ"),
            def(PredefinedPermissions.TICKET_DELETE,
                "/tickets/**", PermissionMethod.DELETE, ResourceType.TICKET,
                "Xóa yêu cầu hỗ trợ"),

            // ── Document (CREATE = ingest) ────────────────────────────────
            def(PredefinedPermissions.DOCUMENT_ALL,
                "/documents/**", PermissionMethod.ALL, ResourceType.DOCUMENT,
                "Toàn quyền tài liệu"),
            def(PredefinedPermissions.DOCUMENT_READ,
                "/documents/**", PermissionMethod.GET, ResourceType.DOCUMENT,
                "Xem tài liệu"),
            def(PredefinedPermissions.DOCUMENT_CREATE,
                "/documents/**", PermissionMethod.POST, ResourceType.DOCUMENT,
                "Tạo tài liệu"),
            def(PredefinedPermissions.DOCUMENT_UPDATE,
                "/documents/**", PermissionMethod.PUT, ResourceType.DOCUMENT,
                "Cập nhật tài liệu"),
            def(PredefinedPermissions.DOCUMENT_DELETE,
                "/documents/**", PermissionMethod.DELETE, ResourceType.DOCUMENT,
                "Xóa tài liệu")

        ));

        list.addAll(List.of(

            // ── ChatModel ────────────────────────────────────────────────
            def(PredefinedPermissions.CHAT_MODEL_ALL,
                "/chat-models/**", PermissionMethod.ALL, ResourceType.CHAT_MODEL,
                "Toàn quyền mô hình chat"),
            def(PredefinedPermissions.CHAT_MODEL_READ,
                "/chat-models/**", PermissionMethod.GET, ResourceType.CHAT_MODEL,
                "Xem mô hình chat"),
            def(PredefinedPermissions.CHAT_MODEL_CREATE,
                "/chat-models", PermissionMethod.POST, ResourceType.CHAT_MODEL,
                "Tạo mô hình chat"),
            def(PredefinedPermissions.CHAT_MODEL_UPDATE,
                "/chat-models/**", PermissionMethod.PUT, ResourceType.CHAT_MODEL,
                "Cập nhật mô hình chat"),
            def(PredefinedPermissions.CHAT_MODEL_DELETE,
                "/chat-models/**", PermissionMethod.DELETE, ResourceType.CHAT_MODEL,
                "Xóa mô hình chat"),

            // ── Conversation ─────────────────────────────────────────────
            def(PredefinedPermissions.CONVERSATION_ALL,
                "/conversations/**", PermissionMethod.ALL, ResourceType.CONVERSATION,
                "Toàn quyền hội thoại"),
            def(PredefinedPermissions.CONVERSATION_READ,
                "/conversations/**", PermissionMethod.GET, ResourceType.CONVERSATION,
                "Xem hội thoại"),
            def(PredefinedPermissions.CONVERSATION_CREATE,
                "/conversations", PermissionMethod.POST, ResourceType.CONVERSATION,
                "Tạo hội thoại mới"),
            def(PredefinedPermissions.CONVERSATION_DELETE,
                "/conversations/**", PermissionMethod.DELETE, ResourceType.CONVERSATION,
                "Xóa hội thoại"),

            // ── Message ──────────────────────────────────────────────────
            def(PredefinedPermissions.MESSAGE_ALL,
                "/messages/**", PermissionMethod.ALL, ResourceType.MESSAGE,
                "Toàn quyền tin nhắn"),
            def(PredefinedPermissions.MESSAGE_READ,
                "/messages/**", PermissionMethod.GET, ResourceType.MESSAGE,
                "Xem tin nhắn"),
            def(PredefinedPermissions.MESSAGE_SEND,
                "/messages", PermissionMethod.POST, ResourceType.MESSAGE,
                "Gửi tin nhắn"),
            def(PredefinedPermissions.MESSAGE_UPDATE,
                "/messages/*", PermissionMethod.PATCH, ResourceType.MESSAGE,
                "Cập nhật tin nhắn (hoàn tất câu trả lời streaming)"),

            // ── AuditLog ─────────────────────────────────────────────────
            def(PredefinedPermissions.AUDIT_LOG_ALL,
                "/audit-logs/**", PermissionMethod.ALL, ResourceType.AUDIT_LOG,
                "Toàn quyền nhật ký hệ thống"),
            def(PredefinedPermissions.AUDIT_LOG_READ,
                "/audit-logs/**", PermissionMethod.GET, ResourceType.AUDIT_LOG,
                "Xem nhật ký hệ thống"),

            // ── LlmTraceLog ──────────────────────────────────────────────
            def(PredefinedPermissions.LLM_TRACE_LOG_ALL,
                "/llm-trace-logs/**", PermissionMethod.ALL, ResourceType.LLM_TRACE_LOG,
                "Toàn quyền nhật ký LLM"),
            def(PredefinedPermissions.LLM_TRACE_LOG_READ,
                "/llm-trace-logs/**", PermissionMethod.GET, ResourceType.LLM_TRACE_LOG,
                "Xem nhật ký LLM"),

            // ── SystemConfig ─────────────────────────────────────────────
            def(PredefinedPermissions.SYSTEM_CONFIG_READ,
                "/system-configs/**", PermissionMethod.GET, ResourceType.SYSTEM_CONFIG,
                "Xem cấu hình hệ thống"),
            def(PredefinedPermissions.SYSTEM_CONFIG_UPDATE,
                "/system-configs/**", PermissionMethod.PUT, ResourceType.SYSTEM_CONFIG,
                "Cập nhật cấu hình hệ thống")
        ));

        return list;
    }

    // ─── Role helpers ────────────────────────────────────────────────────
    private Role seedRole(String name, String description, boolean isSystemRole) {
        return roleRepository.findByName(name).orElseGet(() -> {
            Role r = Role.builder()
                    .name(name)
                    .description(description)
                    .isSystemRole(isSystemRole)
                    .isActive(true)
                    .build();
            roleRepository.save(r);
            log.info("  Role seeded: {}", name);
            return r;
        });
    }

    /**
     * SUPER_ADMIN gets every _ALL permission, plus SystemConfig's READ/UPDATE — SystemConfig has
     * no _ALL wildcard (only GET/PUT are ever exposed, no create/delete), so it is granted
     * explicitly here instead.
     */
    private void assignSuperAdmin(Role role, Map<String, Permission> perms) {
        int count = 0;
        for (Permission p : perms.values()) {
            if (p.getName().endsWith("_ALL")) {
                assignPermission(role, p);
                count++;
            }
        }
        List<String> explicit = List.of(
            permKey(PredefinedPermissions.SYSTEM_CONFIG_READ),
            permKey(PredefinedPermissions.SYSTEM_CONFIG_UPDATE)
        );
        assign(role, perms, explicit);
        count += explicit.size();
        log.info("  {} permissions assigned to role '{}'.", count, role.getName());
    }

    /**
     * INGEST_ADMIN — manages knowledge base, documents, chat models.
     * Does NOT have user/role/permission management.
     */
    private void assignIngestAdmin(Role role, Map<String, Permission> perms) {
        List<String> keys = List.of(
            permKey(PredefinedPermissions.DEPARTMENT_READ),
            permKey(PredefinedPermissions.DOCUMENT_ALL),
            permKey(PredefinedPermissions.CATEGORY_ALL),
            permKey(PredefinedPermissions.CHAT_MODEL_READ),
            permKey(PredefinedPermissions.LLM_TRACE_LOG_READ),
            // Without this, the document create/edit form's "Cấp độ truy cập tối thiểu"
            // dropdown can't load its options (GET /access-levels -> 403), and the
            // ingestion wizard then refuses to process any document left with no access
            // level assigned as a result.
            permKey(PredefinedPermissions.ACCESS_LEVEL_READ),
            // The chunking-strategy step of the ingestion wizard prefills its defaults from
            // GET /system-configs (category=INGEST) instead of a hardcoded FE constant -
            // read-only, no SYSTEM_CONFIG_UPDATE (that stays SUPER_ADMIN-only).
            permKey(PredefinedPermissions.SYSTEM_CONFIG_READ)
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
            permKey(PredefinedPermissions.CONVERSATION_ALL),
            permKey(PredefinedPermissions.MESSAGE_READ),
            permKey(PredefinedPermissions.MESSAGE_SEND),
            permKey(PredefinedPermissions.MESSAGE_UPDATE),
            permKey(PredefinedPermissions.DOCUMENT_READ),
            permKey(PredefinedPermissions.DEPARTMENT_READ),
            permKey(PredefinedPermissions.CATEGORY_READ),
            permKey(PredefinedPermissions.CHAT_MODEL_READ),
            permKey(PredefinedPermissions.USER_READ),
            permKey(PredefinedPermissions.USER_UPDATE)
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
    private User seedUser(String email, String code,
                          String firstName, String lastName, String phone,
                          Role role, String rawPassword, AccessLevel accessLevel) {
        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) return existing.get();

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
        user = userRepository.save(user);

        log.info("  User seeded: {} [{}]", email, role.getName());
        return user;
    }

    /**
     * Gan user vao TAT CA department hien co, cung 1 access_level - dung cho SUPER_ADMIN, nguoi can
     * quan tri toan bo he thong. user_department_access khong cascade theo cay department, nen phai
     * tao du 1 row cho tung department, ke ca node cha lan con.
     */
    private void assignAllDepartmentsToUser(User user, AccessLevel accessLevel) {
        List<Department> departments = departmentRepository.findAll();
        for (Department d : departments) {
            if (userDepartmentAccessRepository.existsByUserIdAndDepartmentId(user.getId(), d.getId())) {
                continue;
            }
            UserDepartmentAccess access = UserDepartmentAccess.builder()
                    .user(user)
                    .department(d)
                    .accessLevel(accessLevel)
                    .build();
            userDepartmentAccessRepository.save(access);
        }
        log.info("  {} department-access rows seeded for {}.", departments.size(), user.getEmail());
    }

    /** Gan user vao dung 1 department, o 1 access_level cu the - dung cho mock user scoped-department. */
    private void assignDepartmentToUser(User user, String departmentName, AccessLevel accessLevel) {
        Department department = departmentRepository.findByName(departmentName)
                .orElseThrow(() -> new IllegalStateException(
                        "Department not seeded yet: " + departmentName));
        if (userDepartmentAccessRepository.existsByUserIdAndDepartmentId(user.getId(), department.getId())) {
            return;
        }
        UserDepartmentAccess access = UserDepartmentAccess.builder()
                .user(user)
                .department(department)
                .accessLevel(accessLevel)
                .build();
        userDepartmentAccessRepository.save(access);
        log.info("  Department access seeded: {} -> {} (L{}).", user.getEmail(), departmentName,
                accessLevel.getLevel());
    }

    // ─── AccessLevel seeding ───────────────────────────────────────────────
    /** Seeds levels 0 (no restriction) through 5, used by User.accessLevel and UserDepartmentAccess.accessLevel. */
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

        // ── Bo sung theo cau truc storage/private/academic_data ──────────────
        // Khoa (con cua node "Khoa" o tren)
        seedDepartment("KHOA_CN_CO_KHI",          khoa, "Khoa Công nghệ Cơ khí");
        seedDepartment("KHOA_CN_DIEN",            khoa, "Khoa Công nghệ Điện");
        seedDepartment("KHOA_CN_DIEN_TU",         khoa, "Khoa Công nghệ Điện tử");
        seedDepartment("KHOA_CN_DONG_LUC",        khoa, "Khoa Công nghệ Động lực");
        seedDepartment("KHOA_CN_HOA_HOC",         khoa, "Khoa Công nghệ Hóa học");
        seedDepartment("KHOA_CN_MAY_THOI_TRANG",  khoa, "Khoa Công nghệ May - Thời trang");
        seedDepartment("KHOA_CN_NHIET_LANH",      khoa, "Khoa Công nghệ Nhiệt - Lạnh");
        seedDepartment("KHOA_KHOA_HOC_CO_BAN",    khoa, "Khoa Khoa học Cơ bản");
        seedDepartment("KHOA_KHOA_HOC_SUC_KHOE",  khoa, "Khoa Khoa học Sức khỏe");
        seedDepartment("KHOA_KY_THUAT_XAY_DUNG",  khoa, "Khoa Kỹ thuật Xây dựng");
        seedDepartment("KHOA_LUAT_KHCT",          khoa, "Khoa Luật - Khoa học Chính trị");
        seedDepartment("KHOA_NGOAI_NGU",          khoa, "Khoa Ngoại ngữ");
        seedDepartment("KHOA_QTKD",               khoa, "Khoa Quản trị Kinh doanh");
        seedDepartment("KHOA_THUONG_MAI_DU_LICH", khoa, "Khoa Thương mại - Du lịch");

        // Phong ban / don vi truc thuoc
        seedDepartment("BAN_QUAN_LY_KTX",         null, "Ban Quản lý Ký túc xá");
        seedDepartment("PHONG_KHAO_THI_DBCL",     null, "Phòng Khảo thí - Đảm bảo Chất lượng");
        seedDepartment("PHONG_QLKH_HTQT",         null, "Phòng Quản lý Khoa học - Hợp tác Quốc tế");
        seedDepartment("PHONG_TO_CHUC_HANH_CHINH", null, "Phòng Tổ chức - Hành chính");
        seedDepartment("TAP_CHI_KHCN",            null, "Tạp chí Khoa học và Công nghệ");
        seedDepartment("VAN_PHONG_DANG_UY",       null, "Văn phòng Đảng ủy");

        // Doan the
        Department doanThe = seedDepartment("DoanThe", null, null);
        seedDepartment("CONG_DOAN",               doanThe, "Công đoàn Trường");
        seedDepartment("DOAN_HOI_SV",             doanThe, "Đoàn Thanh niên - Hội Sinh viên");

        // Phan hieu / co so
        Department phanHieu = seedDepartment("PhanHieu", null, null);
        seedDepartment("CO_SO_THANH_HOA",         phanHieu, "Cơ sở Thanh Hóa");
        seedDepartment("PHAN_HIEU_QUANG_NGAI",    phanHieu, "Phân hiệu Quảng Ngãi");

        // Trung tam
        Department trungTam = seedDepartment("TrungTam", null, null);
        seedDepartment("TT_GDQP_THE_CHAT",        trungTam, "Trung tâm Giáo dục Quốc phòng - Thể chất");
        seedDepartment("TT_NC_MAY_CONG_NGHIEP",   trungTam, "Trung tâm Nghiên cứu Máy Công nghiệp");
        seedDepartment("TT_NGOAI_NGU",            trungTam, "Trung tâm Ngoại ngữ");
        seedDepartment("TT_QUAN_TRI_HE_THONG",    trungTam, "Trung tâm Quản trị Hệ thống");
        seedDepartment("TT_TIN_HOC",              trungTam, "Trung tâm Tin học");

        // Vien
        Department vien = seedDepartment("Vien", null, null);
        seedDepartment("VIEN_CNSH_THUC_PHAM",     vien, "Viện Công nghệ Sinh học - Thực phẩm");
        seedDepartment("VIEN_DTQT_SAU_DAI_HOC",   vien, "Viện Đào tạo Quốc tế và Sau Đại học");
        seedDepartment("VIEN_TAI_CHINH_KE_TOAN",  vien, "Viện Tài chính - Kế toán");

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
                           ResourceType resource, String description) {}

    private static PermDef def(String name, String path, PermissionMethod method,
                               ResourceType resource, String description) {
        return new PermDef(name, path, method, resource, description);
    }
}
