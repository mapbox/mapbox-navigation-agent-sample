package com.mapbox.via.ui.navsdk

object SearchPinCollisionPriority {
    private const val SelectedSortKey = -1.0

    fun sortKey(resultIndex: Int, selected: Boolean): Double {
        require(resultIndex >= 0) {
            "Result index must not be negative: $resultIndex"
        }
        return if (selected) SelectedSortKey else resultIndex.toDouble()
    }
}
