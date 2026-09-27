# ADR-0005: Dynamic Model Registry — provider access layer in Python

- **Date**: 2026-09-25
- **Status**: Accepted
- **Context story**: `changes/23-09-2026-Dynamic-Model-Registry-Runtime-Failover/plan.md`
- **Decision owners**: huydh

## Context

`unisage-backend` already lets a Super Admin register `ChatModel` credentials, but
`unisage-agent` (Python) never reads that table — it loads
`OPENAI_API_KEY`/`OPENAI_MODEL`/`OPENAI_EMBEDDING_MODEL`/`MULTI_REP_LLM_MODEL`
once from `.env` at startup. This feature makes Java the control plane for LLM
credentials and Python the data plane that reads them at runtime, with hot-reload
and failover. Two design questions needed a deliberate decision before Task 5
(the first Python code that builds a model from the registry) could be written:

1. How does Python's graph layer (`Agent(model=...)` from `pydantic-ai`) actually
   call whatever provider a Super Admin configures at runtime, when that provider
   isn't known until the registry is read?
2. Does the SSRF guard (Task 0.6's `PinnedNetworkBackend`, an httpx transport that
   pins the resolved IP at the socket layer) actually work with whatever answer
   #1 lands on? This is not optional — plan.md's Architecture Decisions section
   already commits to "no code may call a URL from the registry before the SSRF
   guard runs", so the transport has to be inject-able into the chosen path.

## Decision

**Python builds a native PydanticAI `Model` (`OpenAIChatModel`, `AnthropicModel`,
...) per credential from the snapshot, selected by `llmProvider`, instead of
routing every provider through a single `LiteLLMModel` adapter wrapping the
LiteLLM SDK.**

This reverses the adapter-first plan recorded earlier in plan.md's Architecture
Decisions ("LiteLLM đi vào qua adapter `LiteLLMModel(pydantic_ai.models.Model)`").
The spike (`tests/spike/run_litellm_spike.py`, not merged — see Test lock) found:

| # | Criterion | Result |
|---|-----------|--------|
| 1 | `stream_agent_text()` runs unmodified, deltas arrive before the end | PASS — see note below |
| 2 | `Agent(output_type=SomeModel)` works non-streaming (tool-call based structured output) | PASS |
| 3 | `Agent(output_type=SomeModel)` works while streaming | PASS |
| 4 | `RequestUsage` present, non-stream and stream | PASS |
| 5 | Exception on provider error is typed with `status_code` | PASS — `litellm.AuthenticationError` (subclasses `openai.APIError`), not `pydantic_ai.ModelHTTPError` — litellm has its own typed hierarchy, which satisfies the intent of this criterion even though it isn't PydanticAI's own type |
| 6 | Inject a custom `httpx.AsyncClient`/transport (the SSRF guard's pin point) — **the adapter path** | **FAIL** — `litellm.acompletion(client=...)` expects an OpenAI-SDK-shaped client object (it read `.api_key` off whatever was passed) or an `aiohttp.ClientSession` via its separate `shared_session` param; a bare `httpx.AsyncClient` raises `AttributeError` deep inside litellm's OpenAI-compatible handler |
| 7 | Same transport injection — **the native fallback path** (`OpenAIProvider(http_client=...)`) | **PASS** (control check) — the transport saw exactly 1 request, proving the pin actually took effect |
| 8 | `FunctionModel` test doubles (`tests/llm_mocks.py`) unaffected | Not re-verified in the spike's isolated venv (missing unrelated app dependencies there) — architecturally unaffected either way, since test doubles are wired in directly and never touch either the adapter or native model code |
| 9 | Real call against ≥2 providers | Only OpenAI was exercised live (no second provider credential available in this environment) — the blocking finding (#6) is about litellm's client-injection mechanism, not about a specific provider, so this gap doesn't change the decision |

Criterion #6 is the one plan.md's own decision gate (Task 0.2) already wrote a
rule for: *"Không inject được transport ... → bỏ LiteLLM, dùng model native
PydanticAI (`OpenAIChatModel`/`AnthropicModel`...) build từ snapshot, Task 9
phân loại trên exception của SDK provider."* That branch is what this ADR
records as taken. Everything else (1-5) would have passed for the adapter path
too — the decision turns entirely on #6, not on the adapter being broken in
general.

**Consequence for Task 5 and Task 0.6**: `get_graph_models()` builds a
`Model` instance by mapping `ChatModel.llmProvider` → a small, explicit table of
native PydanticAI model classes + provider classes (`openai` →
`OpenAIChatModel`/`OpenAIProvider`, `anthropic` → `AnthropicModel`/
`AnthropicProvider`, ...). `SELF_HOSTED` (OpenAI-compatible custom endpoint) uses
`OpenAIChatModel` with `OpenAIProvider(base_url=...)`, which is the same
mechanism OpenAI-compatible self-hosted servers already use with PydanticAI.
Every one of these provider classes takes `http_client=` in its `Provider`
constructor, so `build_provider_http_client()` (Task 0.6) plugs in identically
for all of them — no per-provider SSRF exception. A credential whose
`llmProvider` isn't in the table is rejected the same way plan.md already
specifies for an unsupported provider (`CHAT_MODEL_PROVIDER_UNSUPPORTED`,
`PROVIDER_TRANSPORT_UNSUPPORTED`) — this was already the design for "provider
not proven to support transport injection", it just now applies to a longer,
explicit list of natively-supported providers instead of "everything LiteLLM
lists".

`litellm` is **not** added to `pyproject.toml`. It was only ever installed into a
throwaway venv (`.venv-spike`, `uv pip install --python .venv-spike/...`,
never touching the committed lockfile) for the spike run.

## Consequences

**Tích cực**:

- SSRF guard integration is proven working (control check #7), with no adapter
  layer between PydanticAI and the transport to worry about staying compatible
  with as either library's internals change.
- One fewer third-party dependency (`litellm`) and one fewer hand-rolled `Model`
  subclass (~250 lines in the spike, reimplementing message conversion,
  streaming-event translation, and usage extraction) that would need to track
  `pydantic_ai`'s internal `ModelResponsePartsManager`/`StreamedResponse` API
  across upgrades — the spike itself needed three rounds of fixes for API
  surface that changed between whatever `pydantic-ai` version the original plan
  assumed and the `>=1.0.0` range actually pinned in `pyproject.toml` (currently
  resolves to `2.50.0`).
- Native provider classes already ship first-party support for OpenAI,
  Anthropic, Google, Groq, Mistral, Cohere, Bedrock, HuggingFace, xAI, Cerebras,
  Ollama, OpenRouter — covering every provider this project has discussed
  supporting.

**Tiêu cực / rủi ro**:

- A provider with no native PydanticAI `Model` class (a genuinely obscure one)
  can't be added by just naming it in the registry the way the LiteLLM path
  would have allowed — it needs a new entry in the provider→class map, reviewed
  the same way `SUPPORTED_LLM_PROVIDERS` already requires on the Java side.
  Accepted: the registry's `SUPPORTED_LLM_PROVIDERS` list was already going to
  gate this regardless of which Python-side mechanism was chosen.
- The provider→class map is new code (small, but new) — Task 5's estimated
  scope grows slightly to include it.

## Alternatives considered

1. **LiteLLM SDK adapter (`LiteLLMModel`), as originally planned.** Rejected on
   spike criterion #6 — can't cleanly pin the SSRF transport, which
   plan.md's Architecture Decisions section already made non-negotiable.
2. **LiteLLM Proxy as a separate service.** Rejected before the spike (see
   plan.md's existing Architecture Decisions) — adds an operational service
   `unisage-backend` doesn't need as the sole control plane.
3. **Replace PydanticAI's graph execution layer entirely, call `litellm.acompletion`
   directly from each node.** Rejected before the spike — would require rewriting
   every graph node, `FunctionModel` test doubles, and the streaming/usage
   plumbing `Cost Tracking` depends on; explicitly out of scope per plan.md.

## Test lock

Spike script: `tests/spike/run_litellm_spike.py` (run against real OpenAI via a
throwaway venv, `.venv-spike/Scripts/python.exe tests/spike/run_litellm_spike.py`
— not part of the committed dependency tree or test suite). Per plan.md Task 0.2
("Files likely touched (spike, không merge)"), the spike files
(`app/core/llm/litellm_model.py`, `tests/spike/`) are **not** merged into this
branch — this ADR is the durable record of what was run and found. Re-running it
requires recreating the venv (`uv pip install --python .venv-spike/Scripts/python.exe
"pydantic-ai-slim[openai,anthropic]>=1.0.0" litellm python-dotenv`) and a real
`OPENAI_API_KEY`.

Going forward, this decision is locked in by:
- Task 5's acceptance criteria (provider→class map, `PROVIDER_TRANSPORT_UNSUPPORTED`
  for anything outside it).
- Task 0.6's `test_no_raw_provider_clients.py` (already planned) extended to also
  assert no `import litellm` anywhere in `app/`.

## References

- `changes/23-09-2026-Dynamic-Model-Registry-Runtime-Failover/plan.md` —
  Architecture Decisions, Task List (Task 0.2, Task 5, Task 0.6)
- `changes/23-09-2026-Dynamic-Model-Registry-Runtime-Failover/todo.md` — Task 0.2,
  Task 5
