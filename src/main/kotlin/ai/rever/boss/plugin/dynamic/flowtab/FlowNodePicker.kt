package ai.rever.boss.plugin.dynamic.flowtab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val PanelBg = FlowTheme.Surface
private val PanelBorder = FlowTheme.Border

/**
 * Searchable node picker. Renders nothing unless [FlowGraphState.pickerRequest]
 * is set. Type to filter, click (or press Enter) to spawn; the request's intent
 * decides whether the new node is free-standing, wired from a port, or spliced
 * into an edge. A full-screen scrim dismisses it.
 */
@Composable
fun FlowNodePicker(state: FlowGraphState) {
    val request = state.pickerRequest ?: return
    var query by remember(request) { mutableStateOf("") }
    val focusRequester = remember(request) { FocusRequester() }
    val results = remember(query, state.registry.all().size) {
        state.registry.all().filter {
            query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true)
        }
    }
    LaunchedEffect(request) { focusRequester.requestFocus() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current.density
        val panelWidthDp = 264.dp
        val panelWpx = panelWidthDp.value * density
        val estHpx = 340f * density
        val ax = request.screenAnchor.x.coerceIn(8f, (constraints.maxWidth - panelWpx - 8f).coerceAtLeast(8f))
        val ay = request.screenAnchor.y.coerceIn(8f, (constraints.maxHeight - estHpx - 8f).coerceAtLeast(8f))

        // Scrim: tap anywhere outside the panel to dismiss.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(request) { detectTapGestures { state.pickerRequest = null } }
        )

        Column(
            modifier = Modifier
                .offset { IntOffset(ax.roundToInt(), ay.roundToInt()) }
                .width(panelWidthDp)
                .shadow(16.dp, RoundedCornerShape(FlowTheme.rLg))
                .clip(RoundedCornerShape(FlowTheme.rLg))
                .background(PanelBg)
                .border(1.dp, PanelBorder, RoundedCornerShape(FlowTheme.rLg))
                // Swallow taps so the scrim doesn't dismiss when interacting.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(6.dp)
        ) {
            // Search field
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FlowTheme.rMd))
                    .background(FlowTheme.Canvas)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, null, tint = FlowTheme.TextPlaceholder, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.fillMaxWidth()) {
                    if (query.isEmpty()) {
                        Text("Search nodes…", color = FlowTheme.TextPlaceholder, fontSize = 14.sp)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = FlowTheme.TextPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(FlowTheme.Accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.Escape -> { state.pickerRequest = null; true }
                                    Key.Enter -> {
                                        results.firstOrNull()?.let { state.resolvePicker(it) }; true
                                    }
                                    else -> false
                                }
                            }
                    )
                }
            }

            Spacer(Modifier.size(6.dp))

            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                if (results.isEmpty()) {
                    Text(
                        "No matching nodes",
                        color = FlowTheme.TextPlaceholder,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                for (spec in results) {
                    PickerRow(spec) { state.resolvePicker(spec) }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(spec: NodeSpec, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlowTheme.rMd))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(FlowTheme.rSm)).background(Color(spec.accent)),
            contentAlignment = Alignment.Center
        ) {
            Icon(spec.icon(), null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(spec.label, color = FlowTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(spec.description, color = FlowTheme.TextFaint, fontSize = 11.sp)
        }
    }
}
