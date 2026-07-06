package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.Serializable

/**
 * A composable system prompt, authorable by a user or an agent. Its parts mirror the
 * mined-prod composition in RISA `rx/prompts.py`: a free-form [base] policy plus three
 * bullet sections ([goals], [rules], [glossary]) that [composeSystemPrompt] renders in
 * a stable order. [toolAllowlist] is NOT part of the rendered text — it is the set of
 * tools an agent node is permitted to call, threaded to the runtime separately (keeping
 * observation/tool output as data, not instructions — F: prompt-injection).
 *
 * Persisted at `prompt:<id>`; ids are tracked in `prompts:index` (see [PromptRegistry]).
 * [version] is a monotonic author-bump for optimistic display, not a schema version.
 */
@Serializable
data class Prompt(
    val id: String,
    val name: String,
    val base: String = "",
    val rules: List<String> = emptyList(),
    val glossary: List<String> = emptyList(),
    val goals: List<String> = emptyList(),
    val toolAllowlist: List<ToolRef> = emptyList(),
    val version: Int = 1,
)

/** Render one bullet section, or "" when empty, in RISA `_fmt` list style. */
private fun section(name: String, items: List<String>): String =
    if (items.isEmpty()) "" else "\n[$name]\n" + items.joinToString("") { "- $it\n" }

/**
 * Deterministic system-prompt assembly: [Prompt.base], then GOALS, RULES, GLOSSARY —
 * each an omitted-when-empty bullet section. Ordering and formatting are fixed so the
 * output is stable and testable (the agent runtime feeds this verbatim as its system
 * message). [Prompt.toolAllowlist] is intentionally excluded from the text.
 */
fun composeSystemPrompt(prompt: Prompt): String = buildString {
    val base = prompt.base.trimEnd()
    if (base.isNotEmpty()) append(base).append('\n')
    append(section("GOALS", prompt.goals))
    append(section("RULES", prompt.rules))
    append(section("GLOSSARY", prompt.glossary))
}
