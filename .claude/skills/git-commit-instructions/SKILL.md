---
name: git-commit-instructions
description: >-
  Apply the UniSage Git workflow when naming branches, choosing branch bases, preparing atomic
  commits, pushing changes, or opening pull requests. Enforce canonical owner/Jira branch names,
  English conventional commits, common-only main branches, secret safety, and non-destructive
  history. Use for any UniSage repository Git operation.
---

# UniSage Git Workflow

## Safety

1. Do not commit or push without explicit user instruction.
2. Never commit credentials, `.env`/`.ENV` secrets, tokens, caches, build output (`target/`), or
   local artifacts.
3. Never force-push or rewrite pushed history without explicit approval.
4. Commit directly to `main` only for explicitly requested common repository bootstrap or
   administrative changes.
5. Preserve unrelated user changes and stop if unexpected modifications appear.

## Branch Names

Use:

```text
<prefix>/<owner>-unisage-<task-number>-<short-name>
```

- Prefix: `feature`, `fix`, or `enhance`.
- Owner: `huy` or `huyen`.
- Task number: canonical Jira number without leading zeroes.
- Short name: concise lowercase ASCII kebab-case.

Validate with:

```regex
^(feature|fix|enhance)/(huy|huyen)-unisage-(0|[1-9][0-9]*)-[a-z0-9]+(?:-[a-z0-9]+)*$
```

Use canonical examples:

```text
feature/huyen-unisage-56-audit-fields-user-relation
feature/huy-unisage-15-jwt-refresh-rotation
fix/huyen-unisage-317-permission-cache-invalidation
```

Reject forms such as:

```text
feature/UNISAGE-56-audit-fields
feature/huyen-unisage-056-audit-fields
feat/huyen-unisage-15-jwt
feature/unisage-15-jwt
```

Do not invent the owner or Jira task. Normalize `UNISAGE-04` to `unisage-4` in branch names.

## Branch Bases

- Keep `main` common-only: repository skills, GitHub metadata, hooks, and shared policy files.
- Do not place application source, framework configuration, dependencies, tests, or build output
  on `main`.
- Create the first application initialization branch from a clean, current `main`.
- Create later features from `main` unless they intentionally form a stacked PR.
- For a stacked PR, use the parent feature branch as the base and document the merge order.

## Commit Format

Use English, no emojis, and an uppercase Jira key:

```text
<type>(<scope>): [UNISAGE-N] <short outcome>

<optional 1-3 line body: why this change, only when the subject alone doesn't explain it>
```

Use `feat`, `fix`, `enhance`, `refactor`, `chore`, `docs`, `test`, `style`, or `revert` based on
the staged diff.

Keep the subject a single line. Add a body only when the *why* isn't obvious from the subject or
diff — skip it for routine changes. Never put verification commands/results, a change-by-change
changelog, or PR-style sections (Context/Root cause, Changes/Fix, Verification) in the commit
message — that level of detail belongs in the PR description (see
`.claude/skills/pr/SKILL.md`), not in git history that every `git log` has to scroll past. A
squash-merge commit follows the same rule: rewrite the squash title/body to this format instead of
letting GitHub concatenate all the individual commit messages.

Do not add a `Co-Authored-By: Claude ...` trailer — the user asked not to have Claude attached to
commits on this repo, overriding the harness's usual default.

## Atomic Workflow

1. Inspect `git status --short --branch`.
2. Review the complete diff and separate unrelated concerns (e.g. an entity/service change vs. an
   infrastructure/tooling change like a new migration tool belong in separate commits, even when
   done in the same session).
3. Stage only one coherent change.
4. Run checks appropriate to the staged scope.
5. Review `git diff --cached` and `git diff --cached --check`.
6. Build the message from the staged diff and actual verification.
7. Commit and push only within the user's authorization.

## Verification Matrix

Run these before committing, and carry the results into the PR description (via
`.claude/skills/pr/SKILL.md`) — not into the commit message itself:

- Skills and PR templates: run `quick_validate.py` for each skill (if present) plus
  `git diff --check`.
- Java/Spring Boot logic (entities, services, controllers, repositories): run `./mvnw test` and
  `./mvnw -DskipTests compile` at minimum; run `./mvnw spring-boot:run` against a real/dev
  database when the change affects startup behavior (schema, security config, auditing).
- Schema/migration changes (Flyway files under `src/main/resources/db/migration/`): verify on a
  throwaway/dev database that the migration(s) apply cleanly from empty, and that
  `spring.jpa.hibernate.ddl-auto=validate` passes afterward — see
  `docs/adr/0003-adopt-flyway-migrations.md` for the pattern.
- Documentation-only changes: run formatting or link checks when available.

Never claim checks that did not run.
