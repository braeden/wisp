package com.assist.llm

/**
 * Backwards-compatible alias for [ToolSpec.ClientTool] (phase-12). Phase-04/06
 * code that constructs `ToolDef(name, description, inputSchemaJson)` keeps
 * compiling — the added `strict` field defaults to `false`. New code may use
 * [ToolSpec] directly to mix in [ToolSpec.ProviderTool]s.
 */
typealias ToolDef = ToolSpec.ClientTool
