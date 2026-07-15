package ai.rever.boss.plugin.dynamic.flowtab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PanelBg = FlowTheme.Surface
private val PanelBorder = FlowTheme.Border
private val FieldBg = FlowTheme.Canvas
private val Muted = FlowTheme.TextFaint

/** Split a textarea into a trimmed, blank-dropped list — one item per line. */
private fun linesToList(text: String): List<String> =
    text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

/** Render a list back into an editable multiline blob (one item per line). */
private fun listToLines(items: List<String>): String = items.joinToString("\n")

/**
 * Thin builder/inspector for one [Prompt]: edit name, base policy, and the three
 * bullet sections (goals/rules/glossary, one item per line), with a live rendered
 * preview of [composeSystemPrompt]. Edits are held locally and pushed via [onChange]
 * (e.g. the host calls [PromptRegistry.upsert]). Deliberately minimal — the registry
 * and composition are the substance; this stays consistent with [FlowTheme].
 */
@Composable
fun PromptBuilder(
    prompt: Prompt,
    modifier: Modifier = Modifier,
    onChange: (Prompt) -> Unit = {},
) {
    var name by remember(prompt.id) { mutableStateOf(prompt.name) }
    var base by remember(prompt.id) { mutableStateOf(prompt.base) }
    var goals by remember(prompt.id) { mutableStateOf(listToLines(prompt.goals)) }
    var rules by remember(prompt.id) { mutableStateOf(listToLines(prompt.rules)) }
    var glossary by remember(prompt.id) { mutableStateOf(listToLines(prompt.glossary)) }

    fun emit() = onChange(
        prompt.copy(
            name = name.trim().ifEmpty { prompt.id },
            base = base,
            goals = linesToList(goals),
            rules = linesToList(rules),
            glossary = linesToList(glossary),
        )
    )

    // Live preview reflects the in-flight edits, not the last-saved prompt.
    val preview = composeSystemPrompt(
        prompt.copy(
            base = base,
            goals = linesToList(goals),
            rules = linesToList(rules),
            glossary = linesToList(glossary),
        )
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(PanelBg)
            .border(1.dp, PanelBorder)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Prompt", color = FlowTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

        Field("Name", name, singleLine = true) { name = it; emit() }
        Field("Base", base, minHeight = 72.dp) { base = it; emit() }
        Field("Goals (one per line)", goals, minHeight = 56.dp) { goals = it; emit() }
        Field("Rules (one per line)", rules, minHeight = 56.dp) { rules = it; emit() }
        Field("Glossary (one per line)", glossary, minHeight = 56.dp) { glossary = it; emit() }

        Spacer(Modifier.width(1.dp))
        Label("System prompt (preview)")
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(FlowTheme.rSm))
                .background(FieldBg)
                .border(1.dp, PanelBorder, RoundedCornerShape(FlowTheme.rSm))
                .padding(8.dp)
        ) {
            SelectionContainer {
                Text(
                    preview.ifEmpty { "(empty)" },
                    color = FlowTheme.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 2.dp))
}

@Composable
private fun Field(
    label: String,
    value: String,
    singleLine: Boolean = false,
    minHeight: androidx.compose.ui.unit.Dp = 0.dp,
    onChange: (String) -> Unit,
) {
    Column {
        Label(label)
        Box(
            Modifier.fillMaxWidth()
                .let { if (minHeight > 0.dp) it.heightIn(min = minHeight) else it }
                .clip(RoundedCornerShape(FlowTheme.rSm))
                .background(FieldBg)
                .border(1.dp, PanelBorder, RoundedCornerShape(FlowTheme.rSm))
                .padding(horizontal = 8.dp, vertical = 7.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                textStyle = TextStyle(color = FlowTheme.TextPrimary, fontSize = 12.sp),
                cursorBrush = SolidColor(FlowTheme.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
