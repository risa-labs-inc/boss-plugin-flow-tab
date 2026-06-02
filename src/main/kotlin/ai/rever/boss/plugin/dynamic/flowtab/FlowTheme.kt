package ai.rever.boss.plugin.dynamic.flowtab

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared design tokens. Introduced from the UI review to stop hand-picking
 * near-duplicate hex values per call site. Semantic colors (error vs warning)
 * and a small radius scale; surfaces/text are consolidated here too.
 */
object FlowTheme {
    // Brand
    val Primary = Color(0xFF2D6CDF)
    val PrimaryTint = Color(0xFF8AB4F8)
    val Accent = Color(0xFF4FC3F7) // cursor / highlight / running

    // Status (one red, one amber — used semantically)
    val Success = Color(0xFF66BB6A)
    val Error = Color(0xFFE5524A)
    val Warning = Color(0xFFE5935B)

    // Surfaces
    val Canvas = Color(0xFF17171B)
    val Surface = Color(0xFF1F1F23)
    val NodeBody = Color(0xFF26262C)
    val Border = Color(0xFF34343C)
    val BorderStrong = Color(0xFF55555F)

    // Text (verified more legible on the dark surfaces)
    val TextPrimary = Color(0xFFEDEDF2)
    val TextMuted = Color(0xFFAFAFB8)
    val TextFaint = Color(0xFF8A8A95)
    val TextPlaceholder = Color(0xFF7E7E88)

    // Radius scale (small icon chips / medium controls / large containers)
    val rSm = 6.dp
    val rMd = 8.dp
    val rLg = 12.dp
}
