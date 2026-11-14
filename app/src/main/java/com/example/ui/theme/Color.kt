package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Brand & Surface Palette — Obsidian Forge IDE Aesthetic
val ForgeCyan = Color(0xFF38BDF8)
val ForgeCyanDeep = Color(0xFF0284C7)
val ForgeEmerald = Color(0xFF34D399)
val ForgeEmeraldDeep = Color(0xFF059669)
val ForgeAmber = Color(0xFFFBBF24)
val ForgeCoral = Color(0xFFF87171)
val ForgeIndigo = Color(0xFF818CF8)

// Dark Surfaces
val ObsidianBg = Color(0xFF0B0F17)
val ObsidianSurface = Color(0xFF111722)
val ObsidianSurfaceHigh = Color(0xFF182030)
val ObsidianSurfaceHighest = Color(0xFF212B3F)
val ObsidianOutline = Color(0xFF2C374E)
val ObsidianText = Color(0xFFE6EDF7)
val ObsidianTextMuted = Color(0xFF94A3B8)

// Light Surfaces
val DaylightBg = Color(0xFFF4F7FB)
val DaylightSurface = Color(0xFFFFFFFF)
val DaylightSurfaceHigh = Color(0xFFE9EFF7)
val DaylightSurfaceHighest = Color(0xFFDCE5F2)
val DaylightOutline = Color(0xFFCBD5E1)
val DaylightText = Color(0xFF0F172A)
val DaylightTextMuted = Color(0xFF475569)

@Immutable
data class SyntaxColors(
    val editorBackground: Color,
    val gutterBackground: Color,
    val gutterText: Color,
    val activeLineBackground: Color,
    val errorLineBackground: Color,
    val plainText: Color,
    val keyword: Color,
    val type: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val annotation: Color,
    val xmlTag: Color,
    val xmlAttr: Color,
)

val DarkSyntaxColors = SyntaxColors(
    editorBackground = Color(0xFF0D1119),
    gutterBackground = Color(0xFF101622),
    gutterText = Color(0xFF64748B),
    activeLineBackground = Color(0xFF162032),
    errorLineBackground = Color(0x33F87171),
    plainText = Color(0xFFE2E8F0),
    keyword = Color(0xFFFF7B72),
    type = Color(0xFF79C0FF),
    string = Color(0xFFA5D6FF),
    comment = Color(0xFF6E7681),
    number = Color(0xFF7EE787),
    annotation = Color(0xFFD2A8FF),
    xmlTag = Color(0xFF7EE787),
    xmlAttr = Color(0xFF79C0FF),
)

val LightSyntaxColors = SyntaxColors(
    editorBackground = Color(0xFFFAFBFD),
    gutterBackground = Color(0xFFF1F5F9),
    gutterText = Color(0xFF64748B),
    activeLineBackground = Color(0xFFEFF6FF),
    errorLineBackground = Color(0x22DC2626),
    plainText = Color(0xFF1E293B),
    keyword = Color(0xFFCF222E),
    type = Color(0xFF0550AE),
    string = Color(0xFF0A3069),
    comment = Color(0xFF6E7781),
    number = Color(0xFF116329),
    annotation = Color(0xFF8250DF),
    xmlTag = Color(0xFF116329),
    xmlAttr = Color(0xFF0550AE),
)

val LocalSyntaxColors = staticCompositionLocalOf { DarkSyntaxColors }
