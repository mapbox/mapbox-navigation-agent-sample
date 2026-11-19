package com.mapbox.via.ui.navsdk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.mapbox.toolkit.R
import com.mapbox.via.ui.ViaFrontendConstants

private const val PIN_SIZE_DP = 35f
private const val ICON_SIZE_DP = 18f
private const val STROKE_WIDTH_DP = 1f
private const val SHADOW_RADIUS_DP = 3f
private const val SHADOW_DY_DP = 1.5f
private const val SHADOW_ALPHA = 31 // ≈12% black, matches the MapGPT pin shadow

/**
 * Draws MapGPT-style circular map pins (ported from the MapGPT iOS `CirclePinView`):
 * a 35dp circle with a Maki category icon — surface fill with content-colored icon by default,
 * inverted when selected. [isDark] swaps surface and content the way the iOS colorsets do; the
 * blue selected search pin stays blue in both. Used by the Dash marker factories and the agent
 * marker manager so every pin on the map shares one look.
 */
class ViaPinDrawer(private val context: Context, val isDark: Boolean = false) {

    private val surface = if (isDark) COLOR_DARK else COLOR_LIGHT
    private val content = if (isDark) COLOR_LIGHT else COLOR_DARK

    private val cache = mutableMapOf<Pair<String, Boolean>, Bitmap>()
    private val searchPinCache = mutableMapOf<Pair<String, Boolean>, Bitmap>()

    fun pinBitmap(category: String?, selected: Boolean): Bitmap {
        val key = SearchPinCategory.iconAssetKey(category)
        return cache.getOrPut(key to selected) { draw(drawableForAsset(key), selected) }
    }

    fun searchPinBitmap(category: String?, selected: Boolean): Bitmap {
        val key = SearchPinCategory.iconAssetKey(category)
        return searchPinCache.getOrPut(key to selected) {
            drawSearchPin(drawableForAsset(key), selected)
        }
    }

    private fun draw(@DrawableRes iconRes: Int, selected: Boolean): Bitmap {
        val density = context.resources.displayMetrics.density
        val pinSize = PIN_SIZE_DP * density
        val padding = (SHADOW_RADIUS_DP + SHADOW_DY_DP) * density
        val bitmapSize = (pinSize + padding * 2).toInt()
        val bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = bitmapSize / 2f

        val strokeWidthPx = STROKE_WIDTH_DP * density
        val fillRadius = pinSize / 2f
        val strokeRadius = fillRadius - strokeWidthPx / 2f

        val fillColor = if (selected) content else surface
        val contentColor = if (selected) surface else content

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            setShadowLayer(
                SHADOW_RADIUS_DP * density,
                0f,
                SHADOW_DY_DP * density,
                Color.argb(SHADOW_ALPHA, 0, 0, 0),
            )
        }
        canvas.drawCircle(center, center, fillRadius, fillPaint)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            color = contentColor
        }
        canvas.drawCircle(center, center, strokeRadius, strokePaint)

        val icon = requireNotNull(ContextCompat.getDrawable(context, iconRes))
        icon.setTint(contentColor)
        val iconHalf = (ICON_SIZE_DP * density / 2f).toInt()
        icon.setBounds(
            (center - iconHalf).toInt(),
            (center - iconHalf).toInt(),
            (center + iconHalf).toInt(),
            (center + iconHalf).toInt(),
        )
        icon.draw(canvas)
        return bitmap
    }

    private fun drawSearchPin(@DrawableRes iconRes: Int, selected: Boolean): Bitmap {
        val density = context.resources.displayMetrics.density
        val pinSize = ViaFrontendConstants.SEARCH_PIN_PILL_SIZE_POINTS.toFloat() * density
        val shadowRadius = ViaFrontendConstants.SEARCH_PIN_SHADOW_RADIUS_POINTS.toFloat() * density
        val shadowY = ViaFrontendConstants.SEARCH_PIN_SHADOW_Y_POINTS.toFloat() * density
        val bitmapWidth = (pinSize + shadowRadius * 2).toInt()
        val bitmapHeight = (pinSize + shadowRadius * 2 + shadowY).toInt()
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centerX = bitmapWidth / 2f
        val centerY = shadowRadius + pinSize / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (selected) SEARCH_SELECTED_FILL else surface
            setShadowLayer(
                shadowRadius,
                0f,
                shadowY,
                Color.argb(SHADOW_ALPHA, 0, 0, 0),
            )
        }
        canvas.drawCircle(centerX, centerY, pinSize / 2f, fill)
        val strokeWidthPoints = if (selected) {
            ViaFrontendConstants.SEARCH_PIN_SELECTED_OUTLINE_POINTS
        } else {
            ViaFrontendConstants.SEARCH_PIN_UNSELECTED_OUTLINE_POINTS
        }
        val strokeWidth = strokeWidthPoints.toFloat() * density
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            color = if (selected) COLOR_LIGHT else content
        }
        canvas.drawCircle(centerX, centerY, pinSize / 2f - strokeWidth / 2f, stroke)
        val icon = requireNotNull(ContextCompat.getDrawable(context, iconRes))
        icon.setTint(if (selected) COLOR_LIGHT else content)
        val iconHalf = (
            ViaFrontendConstants.SEARCH_PIN_GLYPH_SIZE_POINTS * density / 2.0
            ).toInt()
        icon.setBounds(
            (centerX - iconHalf).toInt(),
            (centerY - iconHalf).toInt(),
            (centerX + iconHalf).toInt(),
            (centerY + iconHalf).toInt(),
        )
        icon.draw(canvas)
        return bitmap
    }

    companion object {
        private val COLOR_LIGHT = Color.WHITE
        private val COLOR_DARK = Color.parseColor("#1C1C1E")
        private val SEARCH_SELECTED_FILL = Color.parseColor("#0088FF")

        private val DRAWABLE_BY_ASSET = mapOf(
            "cafe" to R.drawable.ic_maki_cafe,
            "restaurant" to R.drawable.ic_maki_restaurant,
            "fast_food" to R.drawable.ic_maki_fast_food,
            "bar" to R.drawable.ic_maki_bar,
            "fuel" to R.drawable.ic_maki_fuel,
            "charging_station" to R.drawable.ic_maki_charging_station,
            "parking" to R.drawable.ic_maki_parking,
            "lodging" to R.drawable.ic_maki_lodging,
            "grocery" to R.drawable.ic_maki_grocery,
            "shop" to R.drawable.ic_maki_shop,
            "hospital" to R.drawable.ic_maki_hospital,
            "pharmacy" to R.drawable.ic_maki_pharmacy,
            "bank" to R.drawable.ic_maki_bank,
            "airport" to R.drawable.ic_maki_airport,
            "car_repair" to R.drawable.ic_maki_car_repair,
            "car" to R.drawable.ic_maki_car,
        )

        fun preferredCategory(categoryIds: List<String>?): String? =
            SearchPinCategory.preferredCategory(categoryIds)

        @DrawableRes
        fun drawableForAsset(assetKey: String): Int =
            DRAWABLE_BY_ASSET[assetKey] ?: R.drawable.ic_maki_marker

        @DrawableRes
        fun iconResFor(category: String?): Int =
            drawableForAsset(SearchPinCategory.iconAssetKey(category))
    }
}
