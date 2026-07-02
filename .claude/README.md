# Assist — AI Phone Agent (Android/Kotlin)

An on-device AI assistant that drives an Android phone through the Accessibility
APIs to accomplish arbitrary user intents by voice ("open Gmail, find my airline
booking, tell me when it leaves, start navigating to the airport"). An
LLM (Claude) perceives the screen and emits **tool calls** that are translated
into gestures and feedback on the device.

## How to use these docs

Each file in `phases/` is a **self-contained work order**. A model or engineer
should be able to execute one phase with only:

1. That phase's markdown file, and
2. `ARCHITECTURE.md` + `CONVENTIONS.md` (shared context, ~small).

Phases deliberately avoid depending on conversation history so work can be
allocated to separate models/agents to keep context usage low. Every phase
lists its **Prerequisites**, exact **Deliverables** (file paths + interfaces),
**Acceptance criteria**, and **Verification** commands.

## Phase map

| # | Phase | Depends on | Parallelizable with |
|---|-------|-----------|---------------------|
| 00 | [Decisions & feasibility](phases/phase-00-decisions.md) | — | — |
| 01 | [Tooling & build/debug self-loop](phases/phase-01-tooling-build-loop.md) | 00 | — |
| 02 | [App skeleton](phases/phase-02-app-skeleton.md) | 01 | — |
| 03 | [Accessibility service + device tools](phases/phase-03-accessibility-tools.md) | 02 | 04, 05 |
| 04 | [LLM abstraction + Claude client](phases/phase-04-llm-claude.md) | 02 | 03, 05 |
| 05 | [Session DB + context management](phases/phase-05-session-db-context.md) | 02 | 03, 04 |
| 06 | [Agent orchestration loop](phases/phase-06-agent-loop.md) | 03, 04, 05 | — |
| 07 | [Overlay UI (interaction viz + interrupt)](phases/phase-07-overlay-ui.md) | 06 | 08 |
| 08 | [Voice I/O + interruptibility](phases/phase-08-voice-io.md) | 06 | 07 |
| 09 | [Wake word](phases/phase-09-wake-word.md) | 08 | 10, 11 |
| 10 | [System prompt](phases/phase-10-system-prompt.md) | 04 | anytime |
| 11 | [Device deploy & debug](phases/phase-11-device-deploy.md) | 01 | anytime after 02 |
| 12 | [Task memory, fast mode & steering](phases/phase-12-task-memory-fast-mode.md) | 04, 05, 06 | 07, 08 |

## Handoff / parallelization plan

- **Sequential spine:** 00 → 01 → 02, then 06 (integration), then 07/08.
- **First fan-out (after 02):** run **03, 04, 05 in parallel** — different
  packages, no shared files. This is the biggest parallelization win.
- **Second fan-out (after 06):** run **07 (overlay) and 08 (voice) in parallel**.
- **Flexible:** 10 (system prompt) can be drafted anytime after 04; 11 (device
  deploy) anytime after 02; 09 (wake word) last.
- **Capability extension (after 06):** 12 (learned task memory + fast mode +
  mid-conversation steering) layers additively onto the 04/05/06 baseline; it
  extends `llm/`, `data/`, `agent/`, `prompt/` rather than adding an isolated
  module.

Anything that touches a live device or real Claude API needs the human in the
loop (device pairing, API key). Those handoff points are flagged in each phase
under **Human checkpoints**.

## Decisions locked (see phase-00)

- Distribution: **personal sideload via adb** (no Play Store).
- Dev target: **physical Pixel 7 Pro** primary; emulator for fast UI loops.
- Perception: **hybrid** — accessibility node tree by default, screenshot on demand.
- Build loop: **`./gradlew` + `adb` + `logcat`** driven from Bash (no MCP required).
- LLM: **model-agnostic interface**, concrete **Anthropic Claude** implementation,
  tested against the real API.
