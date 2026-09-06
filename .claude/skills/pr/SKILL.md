---
name: pr
description: >-
  Draft, review, create, or update evidence-backed UniSage pull requests from the current branch,
  its actual base, commits, diff, Jira task, verification results, and repository PR template.
  Use when asked to write, improve, publish, or summarize a PR, including stacked PRs.
---

# Write UniSage PR

Produce a concise PR title and body that follow `.github/pull_request_template.md`.

## Workflow

1. Read `.github/pull_request_template.md`.
2. Determine the real comparison base:
   - For an existing PR, read `baseRefName` and `headRefName` from GitHub.
   - For a stacked PR, use its parent feature branch until the dependency is merged.
   - Otherwise use `origin/main`.
3. Inspect:

   ```text
   git status --short --branch
   git log --oneline --decorate <base>..<head>
   git diff --stat <base>...<head>
   git diff --name-status <base>...<head>
   ```

4. Read changed files when commit messages and filenames do not establish behavior.
5. Derive the Jira key from the requested title, branch, or commits, in that order. Normalize
   zero-padded identifiers such as `UNISAGE-04` to `UNISAGE-4`.
6. Include only verification that actually ran. Use exact commands and outcomes
   (`./mvnw test`, `./mvnw -DskipTests compile`, manual `spring-boot:run` checks, etc.).
7. Draft the PR. Create or update the remote PR only when the user explicitly requests it.

## Title

Use English and this format:

```text
<type>: [UNISAGE-N] <concise outcome>
```

Choose `feat`, `fix`, `enhance`, `refactor`, `chore`, or `docs`. Keep the title under 72
characters when practical.

## Body

- Preserve `<!-- jira-link:start -->` and `<!-- jira-link:end -->`.
- Link Jira as `[UNISAGE-N](https://tranngochuyen.atlassian.net/browse/UNISAGE-N)`.
- Explain motivation and behavior rather than listing files.
- Use 2-5 bullets in `Change`.
- State affected entities, endpoints, DB schema/migrations, services, and developer workflows in
  `Impact`.
- Include a compact Mermaid diagram for architecture, request flow, or three or more connected
  components (e.g. auditing flow, RBAC resolution, migration sequencing). Omit `Flow` for
  isolated style, docs, or config changes.
- Write exact commands and results in `Test`.
- Write `None` in `Note` when there are no dependencies, limitations, or migration steps. For a
  schema-migration change, state the migration file(s) involved and any one-time manual step
  (e.g. resetting a dev database's `flyway_schema_history`) instead of `None`.
- For stacked PRs, identify the dependency and the base-change sequence in `Note`.

## Output

When drafting only, return exactly:

```markdown
## PR Title

<title>

## PR Description

<completed repository template>
```

When publishing, report the PR URL, base, head, and whether it is mergeable.
