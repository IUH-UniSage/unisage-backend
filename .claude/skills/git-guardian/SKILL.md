---
name: git-guardian
description: >-
  Validate UniSage branch names, branch boundaries, staged diffs, secret safety, atomicity, and
  proposed commit messages before a commit or push. Use when reviewing Git changes, preparing a
  commit, checking whether files belong on main or a feature branch, or diagnosing a branch that
  may violate repository conventions.
---

# Guard UniSage Git Changes

Use `git-commit-instructions` as the source of truth for naming and commit format. Act as an
independent validation pass before execution.

## Workflow

1. Inspect `git status --short --branch`.
2. Validate the current branch name against:

   ```regex
   ^(feature|fix|enhance)/(huy|huyen)-unisage-(0|[1-9][0-9]*)-[a-z0-9]+(?:-[a-z0-9]+)*$
   ```

3. Inspect the complete unstaged and staged diff.
4. Check branch boundaries:
   - `main` may contain only repository skills, GitHub metadata, hooks, and shared policy files.
   - Application source (`src/main`, `src/test`), `pom.xml`, migration files, and build output
     (`target/`) belong on an initialization or feature branch.
   - Stacked branches must identify their parent branch.
5. Scan staged paths and content for secrets (`.ENV`, `.env`, JWT/encryption keys, DB
   credentials), caches, local artifacts, reports, and build output (`target/`, `.mvn/wrapper`
   changes are usually not intentional).
6. Confirm the staged change is one coherent concern and that the commit type matches the diff —
   e.g. an entity/service behavior change and a schema-migration-tooling change (like adopting
   Flyway) are two different concerns even if authored in the same session.
7. Run scope-appropriate checks (see `git-commit-instructions`'s Verification Matrix) and inspect
   `git diff --cached --check`.
8. Compare the proposed commit message with the staged diff.
9. Report pass/fail findings before commit execution.

## Commit Review

Require English conventional commits without emojis:

```text
<type>(<scope>): [UNISAGE-N] <short outcome>

<Context/Root cause>:
- <why>

<Changes/Fix>:
- <what>

Verification:
- <actual checks and outcomes>
```

Reject messages that describe intent instead of the staged diff, omit the Jira key, use the wrong
type, or claim verification that did not run.

## Output

Return blockers first, then the validated branch/base, staged scope, proposed commit message, and
checks. Do not execute commit, push, force-push, or history rewriting unless the user explicitly
authorizes it.
