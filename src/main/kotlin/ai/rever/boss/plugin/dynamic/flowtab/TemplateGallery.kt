package ai.rever.boss.plugin.dynamic.flowtab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight template gallery: an overlay listing the bundled starter templates from
 * [TemplateCatalog], each instantiable into a new tab. Deliberately minimal (a scrim +
 * a card of rows) and consistent with [FlowTheme]; the substance is the catalog +
 * [FlowTemplates] export/import, not the chrome.
 *
 * [onPick] receives the chosen entry's raw JSON so the caller reuses the exact
 * open-in-new-tab path a file import uses. [onDismiss] closes the overlay.
 */
@Composable
fun TemplateGallery(
    catalog: TemplateCatalog,
    onPick: (TemplateEntry) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember(catalog) { catalog.all() }

    // Scrim: click outside the card to dismiss.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xCC0B0B0E))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        // The card swallows clicks so they don't fall through to the scrim.
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 520.dp)
                .heightIn(max = 460.dp)
                .clip(RoundedCornerShape(FlowTheme.rMd))
                .background(FlowTheme.Surface)
                .border(1.dp, FlowTheme.Border, RoundedCornerShape(FlowTheme.rMd))
                .clickable(enabled = false) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Templates", color = FlowTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    "Close",
                    color = FlowTheme.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            if (entries.isEmpty()) {
                Text("No templates available.", color = FlowTheme.TextFaint, fontSize = 12.sp)
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (entry in entries) TemplateRow(entry) { onPick(entry) }
                }
            }
        }
    }
}

@Composable
private fun TemplateRow(entry: TemplateEntry, onUse: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlowTheme.rSm))
            .background(FlowTheme.Canvas)
            .border(1.dp, FlowTheme.Border, RoundedCornerShape(FlowTheme.rSm))
            .clickable(onClick = onUse)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = FlowTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (entry.description.isNotBlank()) {
                Text(entry.description, color = FlowTheme.TextMuted, fontSize = 11.sp)
            }
            val n = entry.snapshot.nodes.size
            Text("$n node(s)", color = FlowTheme.TextFaint, fontSize = 10.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "Use",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(FlowTheme.rSm))
                .background(FlowTheme.Primary)
                .clickable(onClick = onUse)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}
