package com.mapbox.via.ui.navsdk

/**
 * Shared Search Box category → Maki asset mapping. Android already used this for circular pins;
 * iOS search chips use the same keys so a cafe is a cafe on both maps.
 */
object SearchPinCategory {
    const val MARKER = "marker"

    /**
     * Ranks icon keys most-specific-first so that a generic bucket ("food")
     * or an incidental category ("bar" at a restaurant) never wins over the
     * category the place is actually pinned for. Search Box returns category
     * IDs alphabetically sorted, so input order carries no intent.
     */
    private val PIN_CATEGORY_PRIORITY = listOf(
        "charging_station", "gas_station", "parking_lot",
        "airport", "airport_terminal", "hospital", "pharmacy",
        "hotel", "lodging", "cafe", "coffee", "coffee_shop", "restaurant", "fast_food", "bar",
        "grocery", "bank", "atm", "auto_repair", "car_dealership", "food", "shopping",
    )

    /** Canonical Search Box category IDs with a dedicated pin icon, to the shared asset key. */
    private val ASSET_BY_CATEGORY = mapOf(
        "cafe" to "cafe",
        "coffee" to "cafe",
        "coffee_shop" to "cafe",
        "restaurant" to "restaurant",
        "food" to "fast_food",
        "fast_food" to "fast_food",
        "bar" to "bar",
        "gas_station" to "fuel",
        "charging_station" to "charging_station",
        "parking_lot" to "parking",
        "lodging" to "lodging",
        "hotel" to "lodging",
        "grocery" to "grocery",
        "shopping" to "shop",
        "hospital" to "hospital",
        "pharmacy" to "pharmacy",
        "bank" to "bank",
        "atm" to "bank",
        "airport" to "airport",
        "airport_terminal" to "airport",
        "auto_repair" to "car_repair",
        "car_dealership" to "car",
        // iOS Search Box `iconName` is already a Maki key (`fuel`, not `gas_station`).
        "fuel" to "fuel",
        "parking" to "parking",
        "car_repair" to "car_repair",
        "car" to "car",
        "shop" to "shop",
    )

    /** Generic Search Box buckets that should not replace an unmapped `iconName`. */
    private val GENERIC_FALLBACK_BUCKETS = setOf("food", "shopping")

    fun preferredCategory(categoryIds: List<String>?): String? {
        if (categoryIds.isNullOrEmpty()) return null
        val resolved = categoryIds.mapNotNull(::resolveIconKey)
        return PIN_CATEGORY_PRIORITY.firstOrNull { it in resolved } ?: categoryIds.first()
    }

    fun iconAssetKey(category: String?): String =
        resolveIconKey(category)?.let(ASSET_BY_CATEGORY::getValue) ?: MARKER

    fun iconAssetKeyForIds(categoryIds: List<String>?): String =
        iconAssetKey(preferredCategory(categoryIds))

    fun pinCategory(iconName: String?, categoryIds: List<String>?): String? {
        if (resolveIconKey(iconName) != null) return iconName
        val preferred = preferredCategory(categoryIds)
            ?.takeIf { resolveIconKey(it) != null && normalize(it) !in GENERIC_FALLBACK_BUCKETS }
        return preferred ?: iconName
    }

    private fun normalize(category: String?): String? =
        category?.trim()?.lowercase()?.replace('-', '_')?.takeIf { it.isNotEmpty() }

    /** Category IDs without their own asset, folded onto the mapped category they read as. */
    private val ALIAS_BY_CATEGORY = mapOf(
        "juice_bar" to "fast_food",
        "salad_bar" to "fast_food",
        "snack_bar" to "fast_food",
        "food_court" to "fast_food",
        "food_truck" to "fast_food",
        "motel" to "lodging",
        "hostel" to "lodging",
        "supermarket" to "grocery",
        "convenience_store" to "grocery",
        "shopping_mall" to "shopping",
    )

    /** Suffix fallbacks for the long tail of Search Box IDs (`sushi_restaurant`, `wine_bar`, ...). */
    private val ALIAS_BY_SUFFIX = listOf(
        "_restaurant" to "restaurant",
        "_bar" to "bar",
        "pub" to "bar",
        "_cafe" to "cafe",
        "coffeehouse" to "cafe",
        "shop" to "shopping",
        "_store" to "shopping",
    )

    private fun resolveIconKey(category: String?): String? {
        val id = normalize(category) ?: return null
        return id.takeIf { it in ASSET_BY_CATEGORY }
            ?: ALIAS_BY_CATEGORY[id]
            ?: ALIAS_BY_SUFFIX.firstOrNull { (suffix, _) -> id.endsWith(suffix) }?.second
    }
}

sealed interface PoiPinContent {
    data class Rating(val value: String) : PoiPinContent
    data class Category(val assetKey: String) : PoiPinContent
}

fun poiPinContent(
    rating: String?,
    category: String?,
    categoryIds: List<String>? = null,
): PoiPinContent {
    val stars = rating?.trim()?.takeIf { it.isNotEmpty() }
    if (stars != null) return PoiPinContent.Rating(stars)
    val ids = categoryIds ?: listOfNotNull(category)
    return PoiPinContent.Category(SearchPinCategory.iconAssetKeyForIds(ids.ifEmpty { null }))
}
