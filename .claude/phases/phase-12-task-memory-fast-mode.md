# Phase 12 — Learned Task Memory, Fast Mode & Mid-Conversation Steering

**Objective:** make the agent (a) *learn* and *reuse* common task procedures so
repeat intents (e.g. "set YouTube playback speed to 2x") get faster and cheaper
over time, (b) support a user-toggleable **fast mode**, and (c) steer a running
session with **mid-conversation system messages** (barge-in, budget/mode
changes) without breaking the prompt cache.

**Prerequisites:** phase-04 (LlmClient + Claude impl), phase-05 (session DB),
phase-06 (agent loop). This is a **cross-cutting extension** of those phases, not
a greenfield module — it adds to `llm/`, `llm/anthropic/`, `data/`, `agent/`,
`prompt/`, and settings UI. Everything here is designed to be **additive** so it
merges cleanly onto the 04/05/06 baseline.

**Required reading (Anthropic docs — cached notes below, but re-fetch for exact
shapes):**
- Fast mode — https://platform.claude.com/docs/en/build-with-claude/fast-mode
- Memory tool — https://platform.claude.com/docs/en/agents-and-tools/tool-use/memory-tool
- Mid-conversation system messages — https://platform.claude.com/docs/en/build-with-claude/mid-conversation-system-messages
- Tool use overview — https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview
- Context editing / compaction (already used in phase-04).

---

## Key API facts (verified from the docs, 2026-07)

### Memory tool (`memory_20250818`)
- **GA — no beta header.** Works on all Claude 4+ models.
- Declared as a tool entry: `{"type": "memory_20250818", "name": "memory"}` — no
  input schema (Anthropic-provided). Java SDK: `BetaMemoryTool20250818` +
  `BetaMemoryToolHandler` (+ tool runner), in the SDK's **beta** namespace even
  though the feature is GA.
- **Client-side execution.** Claude emits `tool_use` blocks requesting file ops;
  **our app executes them** against storage we control and returns a
  `tool_result`. Commands: `view` (dir listing or file w/ optional
  `view_range`), `create` (`file_text`), `str_replace` (`old_str`/`new_str`),
  `insert` (`insert_line`/`insert_text`), `delete`, `rename`
  (`old_path`/`new_path`). Exact return/error strings are in the doc — mirror
  them (Claude reads them).
- All paths are under `/memories` (a **prefix we map** onto app storage).
- The API auto-adds a "ALWAYS VIEW YOUR MEMORY DIRECTORY FIRST" protocol to the
  system prompt when the tool is present — **do not duplicate it**.
- Pairs with **context editing** (clear stale tool results client-side) and
  **compaction** (server summarization). Memory persists what must survive both.
- **Security is our responsibility:** path-traversal protection (reject anything
  that escapes `/memories`, incl. `../`, `..\\`, `%2e%2e%2f`), size caps, and
  never writing secrets. See the doc's "Security considerations".

### Fast mode
- `speed: "fast"` **+ beta header `fast-mode-2026-02-01`**. Java SDK:
  `MessageCreateParams.Speed.FAST` + `AnthropicBeta.FAST_MODE_2026_02_01`.
- **Opus 4.8 / 4.7 only.** On `claude-opus-4-6` it silently runs standard; on any
  other model `speed:"fast"` is a **400**. So only send it when
  `model ∈ {claude-opus-4-8, claude-opus-4-7}`.
- **Research preview — requires account-manager access / waitlist.** Build the
  plumbing; expect it may 403/unavailable until access is granted. Degrade to
  standard cleanly.
- Premium pricing (opus-4-8: **$10 in / $50 out per MTok**, 2× standard). Boosts
  **output tokens/sec ~2.5×**, not TTFT; shines with streaming.
- Response `usage.speed` = `"fast"|"standard"` — **record it** to confirm.
- Switching speed **invalidates the prompt cache** (different-speed requests
  don't share a cached prefix) — so treat speed as a per-session setting, not
  per-turn thrash.
- Dedicated rate limit; `429` + `retry-after` when exceeded. Optional fallback:
  catch the 429 and retry without `speed` (accepting a cache miss).
- Not on Batch API, Priority Tier, or Claude Platform on AWS.

### Mid-conversation system messages
- A `{"role":"system"}` entry appended into the **`messages`** array (Java SDK:
  `MessageParam.Role.SYSTEM`). **Opus 4.8 only, no beta header.**
- **Operator priority above the user**, but does **not** invalidate the cached
  prefix (it's appended after the breakpoint).
- **Placement rules (400 if violated):** must immediately follow a `user` turn
  (incl. one carrying `tool_result` blocks) or an assistant turn ending in a
  server tool use; must be last or immediately precede an `assistant` turn;
  **never between a `tool_use` and its `tool_result`**; **never first**; **no two
  consecutive** system messages.
- Phrase as **context, not override** ("new input arrived from the user: X", "the
  remaining token budget is now Y") — Claude resists user-hostile system text.
- **Not for untrusted content** (tool output, web/screen text) — that stays in
  `tool_result` blocks. On-screen text is untrusted (prompt-injection surface).

### Tool use (extensibility)
- Client tools (ours) and Anthropic-schema tools (memory) coexist in one `tools`
  array. `strict: true` on our custom tools guarantees schema conformance — adopt
  for gesture tools (`tap`, `swipe`, `set_text`, `open_app`).
- `tool_choice` (`auto`/`any`/`tool`/`none`) and parallel tool calls are the
  agent loop's (phase-06) concern; this phase just makes the seam carry them.

---

## Deliverables

### 1. Extensible tool seam (`com.assist.llm`) — additive
Generalize the tool model so it carries both client tools and provider tools
**without leaking Anthropic types** into `llm/`:

```kotlin
sealed interface ToolSpec {
    /** A tool our app executes (device actions, memory-as-recipe helpers). */
    data class ClientTool(
        val name: String,
        val description: String,
        val inputSchemaJson: String,
        val strict: Boolean = false,
    ) : ToolSpec

    /** An Anthropic-provided tool, e.g. type="memory_20250818", name="memory". */
    data class ProviderTool(val type: String, val name: String) : ToolSpec
}
```
- Keep the existing `ToolDef` as a typealias/adapter to `ClientTool` (or convert
  it) so phase-04/06 code keeps compiling. `LlmRequest.tools` becomes
  `List<ToolSpec>` (or add `providerTools: List<ToolSpec.ProviderTool>` if a
  rename is too invasive at merge time — pick the lower-churn option).
- The Anthropic impl maps `ProviderTool("memory_20250818","memory")` to the SDK's
  `BetaMemoryTool20250818`, and `ClientTool` to a schema tool (+`strict`).

### 2. Message-seam additions (`com.assist.llm`) — additive
- Add `Role.SYSTEM` (or a dedicated `LlmMessage` factory) so the message list can
  carry a mid-conversation system turn. Document the placement constraints on the
  type. Phase-05's `buildLlmMessages` and phase-04's mapper must pass these
  through (system turns are **not** persisted as user/assistant history — see §5).
- Add `speed: Speed = STANDARD` to `LlmRequest` where `enum Speed { STANDARD, FAST }`.
- Add `speed: Speed?` to `Usage`/`LlmResponse` (populate from `usage.speed`).

### 3. Memory subsystem (`com.assist.memory` + `data/`)
- **`MemoryStore`** — executes the six memory commands over **app-private files**
  at `filesDir/memories`, with strict path-traversal protection, size caps, and
  the exact return/error strings from the doc. Framework-free core (path math +
  string edits) split out for unit tests.
- **`MemoryToolHandler`** (in `llm/anthropic/`) — bridges the SDK's memory-tool
  callback to `MemoryStore`; alternatively, if using the manual loop, the agent
  (phase-06) routes `memory` `tool_use` blocks to `MemoryStore` and returns
  `tool_result`. Keep Anthropic types inside `llm/anthropic/`.
- **`TaskRecipeEntity`** (Room, in `data/`) — a lightweight **index** over recipe
  memory files for UI/search/curation (NOT a second source of truth):
  `id, title, intentKeywords, memoryPath, appPackage, useCount, lastUsedAt,
  createdAt`. Updated when the agent writes/uses a `/memories/tasks/*.md` recipe.
- **Repository glue**: `SessionRepository`/a new `TaskMemoryRepository` exposes
  `listRecipes(): Flow`, `deleteRecipe(id)` (deletes file + row), and a
  `recallHint(intent): List<TaskRecipe>` for optional pre-seeding.

### 4. Fast mode
- **`AnthropicLlmClient`**: when `request.speed == FAST` **and** model ∈ opus-4-8
  /4-7, add `speed=fast` + the `fast-mode-2026-02-01` beta; else send standard.
  Record `usage.speed`. On a fast `429`, either surface a typed retryable error
  or (config) retry once without `speed`.
- **Settings**: a persisted "Fast mode" toggle (data/prefs + a switch in the
  onboarding/settings UI). Default off. Show a note that it requires API access
  and premium pricing.
- **`CostCalculator` (phase-05)**: add fast-mode price rows (opus-4-8 fast
  $10/$50; opus-4-7 fast $30/$150) keyed by `(model, speed)`; price each usage
  row by its recorded speed.
- **`ModelRouter` interplay**: fast mode only applies when the router selects an
  Opus model; for haiku/sonnet steps it's a no-op.

### 5. Mid-conversation steering (`agent/` + `prompt/`)
- **`SessionSteering`** helper the agent loop uses to append a system turn at a
  legal position (after the latest `user`/`tool_result` turn), enforcing the
  placement rules and "no consecutive system" locally. Use cases:
  - **Barge-in:** user speaks while the agent is mid-loop → relay as
    `"New input arrived from the user while you were working: <text>"` after the
    next `tool_result`, so the agent folds it in instead of restarting
    (supersedes the naive interrupt in phase-08).
  - **Budget/context warnings:** `"Remaining token budget is now N; prefer
    finishing over exploring."` from `ContextTracker`.
  - **Mode toggles:** `"Fast mode enabled."` / `"Auto-approve is ON for this
    session."` with operator authority.
- These system turns are **transient steering**, not conversation history: mark
  them so `buildLlmMessages` re-emits them at the right spot but they are stored
  distinctly (a `MessageEntity.kind = "system-steer"`), and never fed as user
  text. Keep them out of anything treated as untrusted input.

### 6. System-prompt hooks (`prompt/`, phase-10)
- Instruct the agent to **check memory first** (the API already injects the memory
  protocol, but reinforce the *task-recipe* convention: where to store
  `/memories/tasks/<slug>.md`, what a good recipe contains — app, entry point,
  ordered steps, gotchas, verification), and to **record a recipe after
  completing a non-trivial novel task**. Keep recipes concise and app-scoped.

---

## Contracts I own (consumed by later work)
- `com.assist.llm.ToolSpec` (+ `Speed`, `Role.SYSTEM`, `speed` on request/usage).
- `com.assist.memory.MemoryStore` + the `/memories` storage layout.
- `com.assist.data.TaskRecipeEntity` + `TaskMemoryRepository`.
- `com.assist.agent.SessionSteering`.

## Steps
1. Seam additions (`ToolSpec`, `Speed`, `Role.SYSTEM`, usage.speed) — compile.
2. `MemoryStore` + unit tests (path traversal, each command's success/error
   strings, size caps). No device needed.
3. Wire the memory tool into the Claude client + agent loop; a JVM/instrumented
   test that Claude issues a `view /memories` then a `create`, and our store
   persists it; a second session `view`s it back.
4. Fast mode plumbing + `usage.speed` assertion (live test, opus-4-8) — skips if
   the account lacks fast-mode access (treat 403/unsupported as skip, not fail).
5. `TaskRecipeEntity` index + repository + settings toggle + cost rows.
6. `SessionSteering` + placement-rule unit tests; barge-in path integrated with
   phase-08.

## Acceptance criteria
- **Learned task round-trip:** in session A the agent records a task recipe to
  `/memories/tasks/*.md`; in a fresh session B it `view`s memory and the recipe
  is present and reused. Deleting the recipe in the UI removes file + index row.
- **Path traversal:** `MemoryStore` rejects `/memories/../secrets` and encoded
  variants (unit-tested).
- **Fast mode:** with access + a real key, an opus-4-8 request with `speed=FAST`
  returns `usage.speed == "fast"`; without access it degrades to standard and the
  test skips cleanly. Cost rows price fast usage at the premium multiplier.
- **Steering:** a system turn appended after a `tool_result` is accepted (no 400);
  placement-rule helper rejects illegal positions; barge-in text reaches the
  model as a system turn, not user history.
- `:app:testDebugUnitTest` green for `MemoryStore`, cost, and steering logic.

## Human checkpoints
- **Fast mode access:** confirm whether the account has fast-mode (research
  preview) access; if not, request via account manager / waitlist. The toggle and
  code ship regardless; live-fast tests skip without access.
- **API key** for the live memory/fast tests (same `ANTHROPIC_API_KEY` seam as
  phase-04).

## Notes / pitfalls
- **Additive-merge discipline:** everything here layers on the 04/05/06 baseline;
  prefer adding fields/variants over renaming to keep merges clean.
- **Speed ≠ per-turn:** changing speed busts the cache; treat as session-level.
- **Memory ≠ dumping ground:** cap sizes, prune stale recipes (`lastUsedAt`),
  never store secrets or full screenshots in memory files.
- **Don't duplicate** the memory protocol system text (the API adds it).
- **System turns are operator context, not history** — keep untrusted screen/tool
  text out of them.
