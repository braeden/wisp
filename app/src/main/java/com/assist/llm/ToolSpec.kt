package com.assist.llm

/**
 * A tool advertised to the model. Generalized (phase-12) so the seam carries both
 * tools **our app executes** ([ClientTool]) and **provider-supplied** tools
 * ([ProviderTool], e.g. Anthropic's `memory_20250818`) without leaking any
 * provider types into `com.assist.llm`. The concrete mapping lives only in
 * `com.assist.llm.anthropic`.
 */
sealed interface ToolSpec {

    /** The tool's advertised name (the `name` the model calls). */
    val name: String

    /**
     * A tool our app executes: device actions, user-interaction, context/economy,
     * and (later) memory-as-recipe helpers. [inputSchemaJson] is a JSON Schema
     * object as a string, kept opaque here so this layer stays serialization-
     * agnostic. [strict] requests provider-enforced schema conformance where
     * supported.
     */
    data class ClientTool(
        override val name: String,
        val description: String,
        val inputSchemaJson: String,
        val strict: Boolean = false,
    ) : ToolSpec

    /**
     * A provider-supplied tool, declared by [type] (e.g. `memory_20250818`) and
     * [name] (e.g. `memory`). No input schema — the provider owns it; execution is
     * routed by the agent. New provider tools drop in here without changing the
     * seam.
     */
    data class ProviderTool(
        val type: String,
        override val name: String,
    ) : ToolSpec
}
