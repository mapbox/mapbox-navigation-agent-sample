@file:Suppress("MagicNumber")

package com.mapbox.via.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The raw color palette from the design, one property per design variable.
 *
 * These are deliberately flat values rather than Material roles: the design names colors by
 * intent (`Text/Secondary`, `Buttons/Floating`) and several have no Material slot at all.
 * [ViaColorSchemes] maps what it can onto a Material [androidx.compose.material3.ColorScheme];
 * the rest reaches the UI through [ViaThemeExtras].
 *
 * Do not read these directly from composables. Go through `MaterialTheme.colorScheme` or
 * `LocalViaThemeExtras.current` so the seeded palettes keep working.
 */
@Suppress("LongParameterList") // One property per design variable; grouping them would obscure the mapping.
internal data class ViaColorPalette(
    val bgPrimary: Color,
    val bgSecondary: Color,
    val bgTertiary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textInverted: Color,
    val textGreen: Color,
    val iconsPrimary: Color,
    val iconsSecondary: Color,
    val iconsTertiary: Color,
    val iconsInverted: Color,
    val buttonsPrimary: Color,
    val buttonsOnPrimary: Color,
    val buttonsSecondary: Color,
    val buttonsTertiary: Color,
    val buttonsFloating: Color,
    val borderPrimary: Color,
    val puck: Color,
    val puckRing: Color,
    val puckShadow: Color,
    val routeFill: Color,
    val routeCasing: Color,
    val alternativeRouteFill: Color,
    val alternativeRouteCasing: Color,
    val pinTextBorder: Color,
    /** The design's `black` — the guidance banner fill, distinctly warmer than pure black. */
    val black: Color,
)

/** Day mode, taken verbatim from the design variables. */
internal val ViaLightColorPalette = ViaColorPalette(
    bgPrimary = Color(0xFFFFFFFF),
    bgSecondary = Color(0xFFF2F4F7),
    bgTertiary = Color(0xFFEAECEF),
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0xFF536275),
    textTertiary = Color(0xFFB4BAC6),
    textInverted = Color(0xFFFFFFFF),
    // The design's Text/Green is #09AA74, which is 2.99:1 on white — under the 3:1 graphics floor this
    // palette holds accents to. Darkened just enough to clear it while keeping the emerald hue.
    textGreen = Color(0xFF099966),
    iconsPrimary = Color(0xFF23262D),
    iconsSecondary = Color(0xFF697388),
    iconsTertiary = Color(0xFF888888),
    iconsInverted = Color(0xFFFFFFFF),
    buttonsPrimary = Color(0xFF000000),
    buttonsOnPrimary = Color(0xFFFFFFFF),
    buttonsSecondary = Color(0xFF0088FF),
    buttonsTertiary = Color(0xFFEAEAEA),
    buttonsFloating = Color(0xFFFFFFFF),
    borderPrimary = Color(0xFFDFE2E8),
    // Puck as placed on every design screen.
    puck = Color(0xFF0088FF),
    puckRing = Color(0xFFFFFFFF),
    puckShadow = Color(0x47808A97),
    // Route & Traffic tokens.
    routeFill = Color(0xFF3064F5),
    routeCasing = Color(0xFF1247B2),
    alternativeRouteFill = Color(0xFF99CBFF),
    alternativeRouteCasing = Color(0xFF3B85CF),
    pinTextBorder = Color(0xFFFFFFFF),
    black = Color(0xFF060E10),
)

/**
 * Night mode. The design has no dark frames, so this is hand-authored: backgrounds climb from
 * the design's own `black`, the text and icon ramps invert, and the two saturated accents
 * ([ViaColorPalette.buttonsSecondary], [ViaColorPalette.textGreen]) are
 * lightened because the day values fall below 4.5:1 against a near-black surface.
 */
internal val ViaDarkColorPalette = ViaColorPalette(
    bgPrimary = Color(0xFF060E10),
    bgSecondary = Color(0xFF12181B),
    bgTertiary = Color(0xFF1C2327),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFB4BAC6),
    textTertiary = Color(0xFF697388),
    textInverted = Color(0xFF060E10),
    textGreen = Color(0xFF2ED39A),
    iconsPrimary = Color(0xFFF2F4F7),
    iconsSecondary = Color(0xFFB4BAC6),
    iconsTertiary = Color(0xFF888888),
    iconsInverted = Color(0xFF060E10),
    buttonsPrimary = Color(0xFFFFFFFF),
    buttonsOnPrimary = Color(0xFF060E10),
    buttonsSecondary = Color(0xFF3AA0FF),
    buttonsTertiary = Color(0xFF2A3237),
    buttonsFloating = Color(0xFF1C2327),
    borderPrimary = Color(0xFF2A3237),
    // Design keeps the day puck in dark mode as well.
    puck = Color(0xFF0088FF),
    puckRing = Color(0xFFFFFFFF),
    puckShadow = Color(0x47808A97),
    // Route & Traffic tokens.
    routeFill = Color(0xFF4394FF),
    routeCasing = Color(0xFF002166),
    alternativeRouteFill = Color(0xFF3060BF),
    alternativeRouteCasing = Color(0xFF00162C),
    pinTextBorder = Color(0xFF060E10),
    black = Color(0xFF060E10),
)
