package com.mapbox.via.ui

import androidx.compose.ui.unit.dp

object ViaFrontendConstants {
    val PaddingSmall = 4.dp
    val PaddingMedium = 8.dp

    /** The design's 12dp step, between [PaddingMedium] and [PaddingDefault]. */
    val PaddingCompact = 12.dp
    val PaddingDefault = 16.dp
    val PaddingExtraLarge = 32.dp
    val PaddingLarge = 100.dp

    val CornerRadiusDefault = 16.dp
    val CornerRadiusLarge = 24.dp
    val CornerRadiusExtraLarge = 48.dp

    val VerticalSpacingSmall = 4.dp

    /** POI card tag chip: 10px horizontal inset. */
    val PoiTagPaddingHorizontal = 10.dp
    val VerticalSpacingLarge = 24.dp
    val VerticalPaddingSmall = 10.dp

    val MaxContentWidth = 480.dp

    /** Search playground's curated limit field; fits its label and three digits. */
    val SearchLimitFieldWidth = 140.dp

    /** Search playground's lat, lng override; fits a placeholder and two coordinates. */
    val SearchCoordinatesFieldWidth = 220.dp

    val DrawerCollapsedHeight = 100.dp

    /** Guidance ETA panel: frame hug height including insets. */
    val GuidanceEtaPanelHeight = 82.dp

    /** Guidance ETA panel padding — top 18, horizontal 16, bottom 12. */
    val GuidanceEtaPanelPaddingTop = 18.dp
    val GuidanceEtaPanelPaddingBottom = 12.dp

    /** Drag handle offset inside the ETA panel; it shares the panel's top inset. */
    val GuidanceEtaHandleTop = 6.dp

    /** Gap between arrival time and remaining stats inside the guidance ETA panel. */
    val GuidanceEtaPanelTitleSpacing = 2.dp

    /**
     * Guidance ETA panel buttons: both the route-overview circle
     * and the End pill are 48 tall — larger than the map's [FloatingButtonSize] chrome.
     */
    val GuidanceEtaButtonSize = 48.dp

    /**
     * Horizontal space the centred ETA text gives up to each button, so the two opaque buttons
     * never paint over it. The design centres the ETA in a 239-wide band inside the panel's 370 content
     * box, i.e. (370 - 239) / 2 per side — wider than
     * [GuidanceEtaButtonSize] because the End pill is a Material button with a 58dp minimum width,
     * not a 48dp circle.
     */
    val GuidanceEtaTextGutter = 65.5.dp
    /** Drag handle on every sheet: 28×4. */
    val DrawerHandleWidth = 28.dp
    val DrawerHandleHeight = 4.dp

    /** POI card drag handle: the 28×4 pill starts 6dp from the card top. */
    val PoiSheetHandleTopInset = 6.dp

    /**
     * Shadow depth for surfaces floating over the map. Depth here must come from a shadow, not
     * Material's `tonalElevation`: tonal elevation blends `primary` into the surface, which turns
     * neutral chrome light blue under our blue seed color. The design calls for neutral surfaces.
     */
    val ElevationFloating = 8.dp

    /**
     * Vertical space reserved for the floating chat input: the 48dp pill plus its 12dp
     * ([PaddingCompact]) outer margin top and bottom.
     */
    val ChatInputReservedHeight = 72.dp

    val BuildStampBandHeight = 16.dp

    const val DRAWER_MID_FRACTION = 0.6f

    /**
     * Fraction of the distance between two [ChatDrawer][com.mapbox.via.ui.shell.ChatDrawer]
     * detents a drag must cross before it commits to the next detent. A fast flick still commits
     * regardless via `velocityThreshold`. Scoped to the chat drawer only — `PoiSheet` and
     * `RouteSheetDetent` keep their own 0.5f threshold.
     */
    const val CHAT_DRAWER_POSITIONAL_THRESHOLD_FRACTION = 0.18f

    /** Active guidance drawer: share of the Collapsed ↔ Mid travel that raises or collapses it. */
    const val GUIDANCE_DRAWER_DRAG_THRESHOLD_FRACTION = 0.2f

    /**
     * Fling speed past which a sheet settles in its drag direction regardless of distance.
     * Shared by the chat drawer, POI sheet, route overview, and guidance results list.
     */
    val SheetFlingVelocityThreshold = 125.dp

    /** POI sheet Half detent. Full clears the map top bar instead of a fraction. */
    const val POI_SHEET_HALF_FRACTION: Float = 0.5f

    /** Figma `POI Card` in guidance (4619:25818): 458pt tall on the 874pt artboard. */
    const val POI_SHEET_ADD_STOP_HALF_FRACTION: Float = 458f / 874f

    /** Figma `Header image` in 4619:25818: a fixed 180pt band, not an aspect ratio. */
    val PoiSheetAddStopHeroHeight = 180.dp

    /** Figma `Ctas` in 4619:25818: "Add a stop" is 181pt; Website and Call split the rest. */
    val PoiSheetAddStopCtaWidth = 181.dp

    /**
     * Height of the POI card carousel plus the gap below it, in points. A plain number rather than
     * a [androidx.compose.ui.unit.Dp]: iOS reads it from Swift to pad search-pin framing, and Dp
     * does not cross the ObjC bridge. `PoiCarouselBandTest` pins it to the Compose values.
     */
    const val POI_CAROUSEL_BAND_POINTS: Double = 92.0

    /** [DrawerCollapsedHeightWithInput] in points, for iOS's search-pin framing during guidance. */
    const val DRAWER_COLLAPSED_WITH_INPUT_POINTS: Double = 113.0

    val CornerRadiusNone = 0.dp

    val PaddingNone = 0.dp

    /** Peek height when the destination card is showing — it needs room above the input. */
    val DrawerCollapsedHeightWithCard = 210.dp

    /**
     * Peek height when the collapsed map drawer hosts the input pill, excluding the home-indicator
     * inset (add navigation-bar padding at the call site, same as [DrawerGuidanceCollapsedHeight]).
     *
     * Collapsed map chat drawer: 113dp — drag handle, 21dp
     * last-utterance echo, and 72dp [ChatInputReservedHeight].
     */
    val DrawerCollapsedHeightWithInput = DRAWER_COLLAPSED_WITH_INPUT_POINTS.dp

    /** Peek height for active guidance: ETA panel (handle inside) + clearance above floating input. */
    val DrawerGuidanceCollapsedHeight = GuidanceEtaPanelHeight + ChatInputReservedHeight

    /** Arrival guidance banner: 378×138, 24dp corners. */
    val GuidanceArrivalBannerCornerRadius = CornerRadiusLarge

    /** Black "Arrived" band: 64dp tall, 12dp padding all round. */
    val GuidanceArrivalBannerHeaderHeight = 64.dp
    val GuidanceArrivalBannerPadding = PaddingCompact

    /** Arrival POI card: 12dp padding, 4dp between name and address. */
    val GuidanceArrivalBannerBodyPaddingHorizontal = PaddingCompact
    val GuidanceArrivalBannerBodyPaddingVertical = PaddingCompact
    val GuidanceArrivalBannerAddressSpacing = VerticalSpacingSmall

    /** Arrival panel grabber inset: tighter than the shared drawer handle band. */
    val GuidanceArrivalDragHandleVerticalPadding = 4.dp

    /** One-line arrival banner: 64dp header + 74dp POI card; the measured height replaces it. */
    val GuidanceArrivalBannerEstimatedHeight = 138.dp

    /** Gap between the arrival banner and the chat sheet when the banner is shown again. */
    val GuidanceArrivalBannerClearance = PaddingCompact

    /** Arrival panel End route row: 4/10 vertical inset below the grabber. */
    val GuidanceArrivalPanelPaddingTop = 4.dp
    val GuidanceArrivalPanelPaddingBottom = 10.dp

    /** Full-width End route pill on the arrival panel: a 48dp tall black capsule. */
    val GuidanceArrivalEndRouteButtonHeight = GuidanceEtaButtonSize

    val GuidanceArrivalPanelHeight =
        GuidanceArrivalPanelPaddingTop +
            GuidanceArrivalEndRouteButtonHeight +
            GuidanceArrivalPanelPaddingBottom

    /**
     * Arrival peek without the home-indicator inset — prefer [guidanceArrivalCollapsedHeight] at
     * call sites so the band tracks handle + End route + input.
     */
    val DrawerGuidanceArrivalCollapsedHeight =
        GuidanceArrivalPanelHeight +
            ChatInputReservedHeight +
            GuidanceArrivalDragHandleVerticalPadding * 2 +
            DrawerHandleHeight

    /** Banner top edge: y=56 under the 53pt status bar in the arrival design. */
    val GuidanceArrivalBannerTopInset = 3.dp

    /**
     * Voice orb diameter. The row around it occupies
     * [ChatInputReservedHeight], the same band as the text input, so toggling voice mode does not
     * move the control and active guidance's fixed peek band still fits it.
     */
    val VoiceOrbSize = 68.dp

    val IconSizeSmall = 16.dp
    val IconSizeLarge = 48.dp
    val ImageSizeLarge = 150.dp
    val ImageSizeExtraLarge = 280.dp

    const val INPUT_MAX_LINES = 5

    /**
     * 7 pt fill inside a 9 pt casing;
     * `line-border` is drawn inside `line-width`.
     */
    const val ROUTE_LINE_WIDTH = 9.0
    const val ROUTE_LINE_CASING_WIDTH = 1.0

    /**
     * Pedestrian route: 13 pt stroke, dash 3 + gap 21 = 24 pt period, no casing.
     * Drawn as true circles, not `line-dasharray`, which stretches up to 2x between integer zooms.
     */
    const val ROUTE_LINE_WALKING_DOT_DIAMETER = 13.0
    const val ROUTE_LINE_WALKING_DOT_SPACING = 24.0

    /** Location puck. */
    const val PUCK_RADIUS = 10.0
    const val PUCK_RING_WIDTH = 3.0
    const val PUCK_HEADING_WIDTH = 10.0
    const val PUCK_HEADING_HEIGHT = 8.0
    const val PUCK_HEADING_TIP_OFFSET = 16.8
    const val PUCK_SHADOW_BLUR = 4.0
    const val PUCK_SHADOW_DY = 4.0

    /** Gap between the map ornaments and the top edge of the chat drawer. */
    val MapOrnamentPadding = 8.dp

    /** Matches the Maps SDK default, which offsets attribution to sit right of the logo. */
    val MapAttributionStartPadding = 92.dp

    val MicButtonSize = 60.dp
    val MicIconSize = 28.dp
    val IconSizeDefault = 24.dp
    val MicProgressStroke = 2.dp

    /** Height of the route preview's car/walk/bike modality pills. */
    val ModalityPillSize = 40.dp

    /** Width of the blue accent bar marking the selected route row. */
    val RouteSelectedAccentBarWidth = 4.dp

    /** Diameter of a sheet's circular close chip. */
    val SheetCloseChipSize = 40.dp

    /** Title, close chip and drag handle share this band. */
    val SheetHeaderHeight = 72.dp

    /**
     * The POI sheet's Half detent, deliberately: the two sheets present back to back and the drawer
     * must not change height between them.
     */
    const val ROUTE_SHEET_HEIGHT_FRACTION: Float = POI_SHEET_HALF_FRACTION

    /** Route preview card. */
    val RouteOverviewCardHeight = 79.dp

    /** Route preview content: two [RouteOverviewCardHeight] cards. */
    val RouteOverviewListHeight = RouteOverviewCardHeight * 2

    /** The design sizes the floating map controls at 44dp. */
    val FloatingButtonSize = 44.dp

    /** Active guidance side buttons. */
    val GuidanceSideButtonSize = 52.dp
    val GuidanceSideButtonIconSize = 30.33f.dp
    val GuidanceSideControlsSpacing = PaddingCompact
    val GuidanceSideControlsGapBelowBanner = PaddingCompact
    val GuidanceSideControlsEdgeInset = PaddingCompact

    /**
     * Top-bar controls on the Chat and Menu screens: 48dp
     * circles 16dp from the side edges and 8dp below the status bar, so the buttons land on the
     * same spot on both screens. Larger than the map's [FloatingButtonSize].
     */
    val HeaderButtonSize = 48.dp
    val HeaderPaddingHorizontal = 16.dp
    val HeaderPaddingVertical = 8.dp

    /** The shell top bar's band below the status bar, in points for Swift's route-preview fit. */
    val SHELL_TOP_BAR_BAND_POINTS: Double = (HeaderButtonSize + HeaderPaddingVertical * 2).value.toDouble()

    /**
     * Menu history: the Recents title sits 28dp below the header band, of which
     * [HeaderPaddingVertical] is already spent by the band itself; 16dp from the title to its rows.
     * Each row is padded 16 with a 16 radius.
     */
    val MenuRecentsTopSpacing = 20.dp
    val MenuSectionTitleSpacing = 16.dp

    /**
     * Menu footer buttons: 20dp side inset, 48dp controls; the Chat pill pads
     * 12 before the icon and 16 after the label with an 8 gap between them. The row is centred in
     * a [ChatInputReservedHeight] band rather than the design's 16dp bottom inset (4dp lower) so the
     * footer controls line up with the chat input pill they replace when the menu opens.
     */
    val MenuFooterPaddingHorizontal = 20.dp
    val MenuFooterButtonSize = 48.dp
    val MenuChatButtonPaddingStart = 12.dp
    val MenuChatButtonPaddingEnd = 16.dp

    /** Search-pin measurements, shared with the SwiftUI renderer. */
    const val SEARCH_PIN_PILL_SIZE_POINTS = 27.0
    const val SEARCH_PIN_GLYPH_SIZE_POINTS = 14.0
    const val SEARCH_PIN_LABEL_WIDTH_POINTS = 140.0
    const val SEARCH_PIN_LABEL_SPACING_POINTS = 8.0
    const val SEARCH_PIN_TEXT_SIZE_POINTS = 13.0
    const val SEARCH_PIN_TEXT_HALO_WIDTH_POINTS = 1.5
    const val SEARCH_PIN_SELECTED_OUTLINE_POINTS = 2.0
    const val SEARCH_PIN_UNSELECTED_OUTLINE_POINTS = 1.0
    const val SEARCH_PIN_SHADOW_RADIUS_POINTS = 4.0
    const val SEARCH_PIN_SHADOW_Y_POINTS = 4.0

    /**
     * Drop the dashed street-snap connector once the marker and routable endpoint are farther
     * than this in the world, even if they still look far apart on screen (zoomed in).
     */
    const val ROUTE_SNAP_CONNECTOR_MAX_OFFSET_METERS = 100.0

    /** Hide the connector when the two points are closer than this in projected map points. */
    const val ROUTE_SNAP_CONNECTOR_MIN_SCREEN_DISTANCE_POINTS = 24.0

    /**
     * Guidance banner sub-banner (lane guidance, "then" preview) background, shared by Android's
     * `GuidanceBannerThemeFactory` and iOS's `GuidanceBannerStyle.swift`. An ARGB [Long] rather
     * than a typed color: neither Compose's `Color` nor `UIColor` cross the ObjC bridge.
     */
    const val GUIDANCE_SUB_BANNER_GREY_ARGB: Long = 0xFF2A2A2A

    /**
     * Android system-bar scrims, copied from androidx `enableEdgeToEdge()` defaults (they are
     * `internal` there). Not design tokens: only drawn on API levels that cannot recolor bar icons
     * themselves, so the icons keep contrast over a light or dark bar.
     */
    const val SYSTEM_BAR_LIGHT_SCRIM_ARGB: Long = 0xE6FFFFFF
    const val SYSTEM_BAR_DARK_SCRIM_ARGB: Long = 0x801B1B1B
}
