package com.assist.agent

import android.util.Log

/**
 * Safety policy: before executing a sensitive/irreversible action the agent must
 * get explicit user confirmation. [classify] is a **pure** function (unit-tested)
 * that decides whether a resolved action is gated and why; [confirm] is the
 * effectful enforcement used by the loop/router (emits [AgentEvent.AwaitingConfirmation],
 * asks via [UserIo], logs every decision).
 *
 * Because our tool catalog is low-level (tap an element, set text), a "send" or
 * "delete" is inferred from the **target element's text/description** plus the
 * tool — the router resolves that into a [GateInput] before executing.
 *
 * Categories a user has pre-approved for the session are carried in
 * [GateInput.allowlist] (configurable in settings) and skip the gate.
 */
class ActionGate {

    /** Pure classification of a resolved action. No side effects. */
    fun classify(input: GateInput): GateDecision {
        val category = categoryFor(input) ?: return GateDecision.allowed()
        if (category.name in input.allowlist) {
            return GateDecision.allowed()
        }
        return GateDecision(
            gated = true,
            category = category,
            reason = category.reason,
        )
    }

    /**
     * Enforce the gate for [input]. Returns true if the action may proceed. For a
     * gated action, emits [AgentEvent.AwaitingConfirmation], asks the user, logs
     * the decision, and proceeds only on an affirmative answer.
     */
    suspend fun confirm(input: GateInput, userIo: UserIo, bus: AgentEventBus): Boolean {
        val decision = classify(input)
        if (!decision.gated) return true
        val category = decision.category!!
        val question = confirmationQuestion(input, category)
        bus.emit(AgentEvent.AwaitingConfirmation(question, category.name))
        val answer = userIo.ask(question)
        val proceed = isAffirmative(answer)
        Log.i(
            TAG,
            "gated ${input.toolName} [${category.name}] target=${input.targetText?.take(60)} " +
                "-> ${if (proceed) "APPROVED" else "DECLINED"}",
        )
        return proceed
    }

    private fun categoryFor(input: GateInput): GateCategory? {
        // Password entry: any set_text into a secure field.
        if (input.toolName == AgentTools.SET_TEXT && input.isPasswordField) {
            return GateCategory.PASSWORD
        }
        // Actions inferred from the element the model is tapping/pressing.
        val actionTools = setOf(
            AgentTools.TAP,
            AgentTools.TAP_XY,
            AgentTools.LONG_PRESS,
            AgentTools.LONG_PRESS_XY,
        )
        if (input.toolName in actionTools || input.toolName == AgentTools.PRESS_KEY) {
            val haystack = input.targetText?.lowercase()?.trim().orEmpty()
            if (haystack.isNotEmpty()) {
                KEYWORDS.firstOrNull { (kw, _) -> haystack.containsWord(kw) }?.let { return it.second }
            }
        }
        return null
    }

    private fun confirmationQuestion(input: GateInput, category: GateCategory): String {
        val target = input.targetText?.takeIf { it.isNotBlank() }?.let { " \"${it.take(60)}\"" }.orEmpty()
        return "Confirm ${category.label} action$target? (yes/no)"
    }

    companion object {
        private const val TAG = "ActionGate"

        /** True if [answer] reads as an affirmative yes. */
        fun isAffirmative(answer: String): Boolean {
            val a = answer.trim().lowercase()
            return a in AFFIRMATIVES || a.startsWith("yes") || a.startsWith("confirm") ||
                a.startsWith("approve") || a.startsWith("go ahead") || a.startsWith("do it")
        }

        private val AFFIRMATIVES = setOf("y", "yes", "yeah", "yep", "ok", "okay", "sure", "confirm", "approved")

        /**
         * Keyword → category, checked against the target element's text. Ordered so
         * the most specific/severe categories win first.
         */
        private val KEYWORDS: List<Pair<String, GateCategory>> = listOf(
            "uninstall" to GateCategory.DELETE,
            "delete" to GateCategory.DELETE,
            "remove" to GateCategory.DELETE,
            "erase" to GateCategory.DELETE,
            "factory reset" to GateCategory.DELETE,
            "format" to GateCategory.DELETE,
            "pay" to GateCategory.PAY,
            "buy" to GateCategory.PAY,
            "purchase" to GateCategory.PAY,
            "checkout" to GateCategory.PAY,
            "place order" to GateCategory.PAY,
            "order now" to GateCategory.PAY,
            "subscribe" to GateCategory.PAY,
            "install" to GateCategory.INSTALL,
            "update all" to GateCategory.INSTALL,
            "send" to GateCategory.SEND,
            "share" to GateCategory.SEND,
            "post" to GateCategory.SEND,
            "tweet" to GateCategory.SEND,
            "reply" to GateCategory.SEND,
            "call" to GateCategory.CALL,
            "dial" to GateCategory.CALL,
        )

        /** Word-boundary contains: avoids "install" matching inside "reinstalling" etc. minimally. */
        private fun String.containsWord(word: String): Boolean {
            if (word.contains(' ')) return contains(word)
            val idx = indexOf(word)
            if (idx < 0) return false
            val before = idx - 1
            val after = idx + word.length
            val okBefore = before < 0 || !this[before].isLetterOrDigit()
            val okAfter = after >= length || !this[after].isLetterOrDigit()
            return okBefore && okAfter
        }
    }
}

/** The resolved facts about an action, fed to [ActionGate.classify]. */
data class GateInput(
    val toolName: String,
    val argsJson: String,
    /** Text/description of the element being acted on (untrusted screen text). */
    val targetText: String? = null,
    /** True if a set_text target is a secure/password field. */
    val isPasswordField: Boolean = false,
    /** Categories the user pre-approved for this session; these skip the gate. */
    val allowlist: Set<String> = emptySet(),
)

/** The outcome of classifying an action. */
data class GateDecision(
    val gated: Boolean,
    val category: GateCategory? = null,
    val reason: String? = null,
) {
    companion object {
        fun allowed() = GateDecision(gated = false)
    }
}

/** Sensitive/irreversible action categories that require confirmation. */
enum class GateCategory(val label: String, val reason: String) {
    SEND("send", "Sends a message/content that cannot be unsent."),
    PAY("payment", "Spends money / completes a purchase."),
    DELETE("delete", "Deletes or removes data irreversibly."),
    INSTALL("install", "Installs or updates software."),
    CALL("call", "Places a phone call."),
    PASSWORD("password entry", "Enters text into a secure/password field."),
    IRREVERSIBLE("irreversible", "Irreversible or high-impact action."),
}
