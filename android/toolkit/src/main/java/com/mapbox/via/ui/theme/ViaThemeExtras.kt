package com.mapbox.via.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * A drop shadow from the design, expressed the way design tools express it: a Gaussian blur [radius], an
 * [offset], and a [color] carrying its own alpha.
 */
data class ViaShadow(
    val radius: Dp,
    val color: Color,
    val offset: DpOffset = DpOffset.Zero,
)

/**
 * Design values that Material has no role for.
 *
 * Material covers most of the palette (see [viaColorScheme]), but the design also names a third
 * text rank, a separate icon ramp, the floating-chrome fill, the map puck, and two named shadows.
 * Those live here so surfaces can be exact instead of approximating with the nearest Material role.
 *
 * Read via `LocalViaThemeExtras.current`. Provided by `ViaTheme` for every palette, so composables can
 * depend on it without caring which palette is active.
 */
@Suppress("LongParameterList") // Flat bag of extras; nesting would only add ceremony at call sites.
data class ViaThemeExtras(
    val textTertiary: Color,
    val textGreen: Color,
    val iconsPrimary: Color,
    val iconsSecondary: Color,
    val iconsTertiary: Color,
    val iconsInverted: Color,
    val buttonsFloating: Color,
    val buttonsSecondary: Color,
    val buttonsTertiary: Color,
    val borderPrimary: Color,
    val puck: Color,
    val puckRing: Color,
    val puckShadow: Color,
    val routeFill: Color,
    val routeCasing: Color,
    val alternativeRouteFill: Color,
    val alternativeRouteCasing: Color,
    val pinTextBorder: Color,
    /** Fill for the guidance banner and other fully inverted chrome. */
    val bannerBackground: Color,
    val bannerContent: Color,
    /** `Day shadow`: cards resting on the map, throwing downward. */
    val dayShadow: ViaShadow,
    /** `Day backwards`: [dayShadow] thrown upward, for sheets whose top edge is the only one shown. */
    val sheetShadow: ViaShadow,
    /** The diffuse lift under floating chrome — map buttons and the chat input pill. */
    val floatingShadow: ViaShadow,
    /** The wider halo the chat drawer casts over the map. */
    val drawerShadow: ViaShadow,
)

// Literal design values -- do not "calibrate" them. `viaShadow` paints
// them with `Modifier.dropShadow`, which takes a blur radius and alpha the same way the design does.
// The elevation-based `Modifier.shadow` cannot: it splits a shadow into an ambient part plus a spot
// part thrown downward, which renders these alphas at ~1% above the shape and loses the upward half
// of the halo entirely.
private val DayShadow = ViaShadow(8.dp, Color(0x1F000000), DpOffset(0.dp, 4.dp))

/**
 * `Day backwards`, on the route preview drawer. A bottom sheet only ever shows its top
 * edge, so the design mirrors [DayShadow]'s offset rather than throwing the halo off screen.
 */
private val SheetShadow = ViaShadow(8.dp, Color(0x1F000000), DpOffset(0.dp, (-4).dp))

/** Floating buttons and the chat input both carry this one. */
private val FloatingShadow = ViaShadow(24.dp, Color(0x1A000000))

/** The chat drawer's halo, on the sheet root. */
private val DrawerShadow = ViaShadow(48.dp, Color(0x1A000000))

internal fun viaThemeExtras(palette: ViaColorPalette): ViaThemeExtras = ViaThemeExtras(
    textTertiary = palette.textTertiary,
    textGreen = palette.textGreen,
    iconsPrimary = palette.iconsPrimary,
    iconsSecondary = palette.iconsSecondary,
    iconsTertiary = palette.iconsTertiary,
    iconsInverted = palette.iconsInverted,
    buttonsFloating = palette.buttonsFloating,
    buttonsSecondary = palette.buttonsSecondary,
    buttonsTertiary = palette.buttonsTertiary,
    borderPrimary = palette.borderPrimary,
    puck = palette.puck,
    puckRing = palette.puckRing,
    puckShadow = palette.puckShadow,
    routeFill = palette.routeFill,
    routeCasing = palette.routeCasing,
    alternativeRouteFill = palette.alternativeRouteFill,
    alternativeRouteCasing = palette.alternativeRouteCasing,
    pinTextBorder = palette.pinTextBorder,
    // The guidance banner is inverted chrome: a dark card in light mode, a light card in dark mode,
    // so it always separates from the surface beneath it. Pinning it to the fixed `black` would make
    // it identical to the dark surface (`bgPrimary` is also #060E10), leaving no visible edge.
    bannerBackground = if (palette.bgPrimary.luminance() < 0.5f) palette.textPrimary else palette.black,
    bannerContent = palette.textInverted,
    dayShadow = DayShadow,
    sheetShadow = SheetShadow,
    floatingShadow = FloatingShadow,
    drawerShadow = DrawerShadow,
)

internal val ViaLightThemeExtras = viaThemeExtras(ViaLightColorPalette)

internal val ViaDarkThemeExtras = viaThemeExtras(ViaDarkColorPalette)

/**
 * Best-effort extras for the seed-generated palettes, which have no design values to draw on.
 * Everything resolves to the nearest Material role so those themes stay usable, if not exact.
 */
internal fun viaThemeExtrasFrom(colorScheme: ColorScheme, isDark: Boolean): ViaThemeExtras {
    val reference = if (isDark) ViaDarkThemeExtras else ViaLightThemeExtras
    return reference.copy(
        textTertiary = colorScheme.onSurfaceVariant,
        textGreen = colorScheme.tertiary,
        iconsPrimary = colorScheme.onSurface,
        iconsSecondary = colorScheme.onSurfaceVariant,
        iconsTertiary = colorScheme.outline,
        iconsInverted = colorScheme.inverseOnSurface,
        buttonsFloating = colorScheme.surface,
        buttonsSecondary = colorScheme.primary,
        buttonsTertiary = colorScheme.surfaceContainerHigh,
        borderPrimary = colorScheme.outlineVariant,
        bannerBackground = colorScheme.inverseSurface,
        bannerContent = colorScheme.inverseOnSurface,
    )
}

/**
 * Defaults to the day extras so previews and tests that stand up a bare `MaterialTheme` still
 * render sensibly instead of throwing.
 */
val LocalViaThemeExtras = staticCompositionLocalOf { ViaLightThemeExtras }

/**
 * Whether `ViaTheme` is dark, for drawing that can't read a color role (map pin bitmaps). Defaults to
 * light, like [LocalViaThemeExtras].
 */
val LocalViaIsDark = staticCompositionLocalOf { false }

/**
 * Casts [shadow] in [shape]. Never clips the content, so a [ViaShapes.Pill] shape does not crop
 * multi-line fields or flush trailing controls to a radius of half the height.
 */
fun Modifier.viaShadow(shadow: ViaShadow, shape: Shape): Modifier = dropShadow(
    shape = shape,
    shadow = Shadow(radius = shadow.radius, color = shadow.color, offset = shadow.offset),
)
