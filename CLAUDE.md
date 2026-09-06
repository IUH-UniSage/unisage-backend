# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Spring Boot (Java 21, Spring Boot 3.4.3) master-data service for Unisage: Auth, User, RBAC, Department, Category, ChatModel, Document, Conversation, Message. Runs at context-path `/api/v1`, default port `8401`. Normally reached through the API Gateway under `/api/v1/master/**`, or called directly in dev.

## Commands

Environment config lives in `.ENV` (copy from `.env.example`; `spring-dotenv` loads it — not the usual `.env` name).

```bash
# Run app via IDE/CLI (DB only in Docker; COMPOSE_PROFILES= empty in .ENV)
docker compose up -d
./mvnw spring-boot:run

# Full Docker run (COMPOSE_PROFILES=app in .ENV)
./mvnw clean package -DskipTests
docker compose up -d --build

# Tests
./mvnw test
./mvnw test -Dtest=ClassName
./mvnw test -Dtest=ClassName#methodName
```

Dev Container (VS Code "Reopen in Container") only starts the `unisage-db` service; run `spring-boot:run`/`test` manually inside it.

## Architecture

**Layering**: `controller` → `service/<domain>` (interface + `*Impl`) → `repository` (Spring Data JPA) → `entity`. DTOs live under `dto/request` and `dto/response`; controllers never expose entities directly.

**Auth is not handled here — it's handled by the Gateway.** `GatewayHeaderFilter` (`security/`) reads the JWT from the `Authorization` header or a cookie (`CookieUtil`), validates it (`JwtUtil`), and builds a `GatewayAuthenticationToken`/`UserPrincipal` from its claims (userId, role, code). There is no login-form/session filter chain here — this service trusts a JWT that was already issued (by the `/auth/**` endpoints) and forwarded through the Gateway.

**Authorization is dynamic and DB-driven, not annotation-based.** `SecurityConfig` routes every request through `DynamicAuthorizationManager`, which:
1. Allows the request unconditionally if it matches `PredefinedPublicPaths.PUBLIC_PATHS` (path + method patterns, e.g. `/auth/**`, `/ai/**`, guest chat endpoints like `POST /conversations`).
2. Otherwise requires an authenticated `UserPrincipal`, loads the `User` with `role.rolePermissions.permission` (`findByIdWithPermissions`), and grants access only if some active `Permission` has an Ant-style `path` + `method` (or `ALL`) matching the request.

This means adding a new endpoint that should be publicly reachable requires adding it to `PredefinedPublicPaths`, not a `@PermitAll`-style annotation. Adding a new *protected* endpoint requires a corresponding `Permission` row to exist (see `PredefinedPermissions`/`PredefinedRoles`, used by `DataInitializer` to seed RBAC data on startup) — a valid JWT alone is not sufficient, the user's role needs the matching permission.

**Errors**: business/domain errors are thrown as `AppException(ErrorCode, [fieldErrors])`, caught by `GlobalExceptionHandler`, which maps them to structured JSON responses. `ErrorCode` is a numbered, grouped enum (e.g. the 24xx range is file/storage errors — see `docs/adr/0001-document-file-upload-validation-policy.md`); when adding a new failure mode, add a new `ErrorCode` in the right range rather than throwing a raw exception or reusing an unrelated code.

**File storage** goes through MinIO (`MinioConfig`, `service/file`), not local disk. Uploads are validated with an extension whitelist (`entity/enums/AllowedFileType`) — intentionally extension-only, not Content-Type sniffing (see ADR 0001 for the reasoning) — and `utils/MultipartRequestValidator` rejects multi-file submissions on a single-file form field at the servlet layer before any service/DB/MinIO work happens.

**Auditing**: entities extend `BaseEntity` (`createdAt`/`createdBy`/`updatedAt`/`updatedBy`/`isActive`), populated via Spring Data JPA auditing (`AuditingConfiguration`) — don't set these fields manually. `createdBy`/`updatedBy` are a `@ManyToOne User` relation (nullable, no seeded "system" user for guest-created rows) rather than a raw ID string — see `docs/adr/0002-audit-fields-user-relation.md`.

**Schema migrations**: schema is owned by Flyway (`src/main/resources/db/migration/`, `V{n}__description.sql`), not `ddl-auto` (set to `validate`, Hibernate only checks the mapping matches, never alters). Any entity change that affects columns/tables needs a new migration file alongside it — see `docs/adr/0003-adopt-flyway-migrations.md` and the "Database migration (Flyway)" section in `README.md` for the workflow and how to handle a pre-Flyway local dev database.

**Design docs**: `docs/adr/` for accepted architectural decisions, `docs/design/` for the ERD/DB schema, `docs/postman/` for an importable request collection.

## When to write an ADR

Write an ADR under `docs/adr/` (copy `docs/adr/0000-template.md`, number it sequentially) when a
change:

- Picks between two or more real alternatives for a cross-cutting concern — how a
  `@MappedSuperclass`-shared field is modeled (see ADR-0002), which migration tool owns the
  schema (see ADR-0003), an auditing/security/authorization approach, or a new dependency that
  replaces a hand-rolled pattern (or vice versa).
- Is likely to be questioned or "fixed back" later by someone who doesn't know why it was done
  this way.
- Would be expensive to reverse once other code depends on it — e.g. it touches a shared
  superclass/base entity, changes a public response shape, or changes how the team manages schema
  or migrations.

Don't write one for routine feature work, a bug fix, or just following an existing convention —
if in doubt, a quick way to check: could you explain the "why" in one sentence just by reading the
code? If yes, no ADR needed. If the reasoning would be lost once the diff is old, write one.

If a later decision replaces an earlier ADR, mark the old one `Status: superseded by NNNN` rather
than deleting it.

## Git workflow

Apply `.claude/skills/git-commit-instructions/SKILL.md` before any branch, commit, or PR operation (branch naming, commit format, `main` stays common-only). Apply `.claude/skills/git-guardian/SKILL.md` before staging anything, and `.claude/skills/pr/SKILL.md` when opening a PR (uses `.github/pull_request_template.md`). Never commit or push without explicit instruction.
