package com.assist.llm

/**
 * Generation speed (phase-12). [FAST] maps to Anthropic fast mode
 * (`speed:"fast"` + the fast-mode beta) — **only** on Opus 4.8/4.7; the impl
 * degrades to standard on any other model. Switching speed busts the prompt
 * cache, so treat it as a session-level setting, not per-turn.
 */
enum class Speed { STANDARD, FAST }
