import Combine
import CoreLocation
import MapboxMaps
@_spi(ExperimentalMapboxAPI) import MapboxNavigationCore
import OSLog
import SwiftUI
import UIKit

struct GuideMapView: UIViewControllerRepresentable {
    @ObservedObject var guide: Guide
    var places: [Place]
    var overlay: ToolOverlay?
    var selectedPlaceID: String?
    /// Height of the voice control, which sits at the bottom of the safe area.
    var voiceControlHeight: CGFloat
    var onSelect: (Place) -> Void

    func makeUIViewController(context: Context) -> GuideMapController {
        GuideMapController(guide: guide)
    }

    func updateUIViewController(_ controller: GuideMapController, context: Context) {
        controller.onSelect = onSelect
        controller.setVoiceControlHeight(voiceControlHeight)
        controller.setOverlay(overlay)
        controller.setPlaces(places, selectedID: selectedPlaceID)
        controller.updateChrome(isNavigating: guide.isNavigating, hasSelection: selectedPlaceID != nil, places: places)
    }
}

/// Bottom margin for the Mapbox logo and info button.
///
/// Ornament margins are measured from the map's safe area. `chromeBottom` is the
/// camera padding from the view's edge, so the home-indicator inset is removed.
/// The voice control is already laid out inside the safe area, and its height
/// wins when Dynamic Type makes the button taller than that padding.
func ornamentBottomMargin(chromeBottom: CGFloat, safeAreaBottom: CGFloat, voiceControlHeight: CGFloat) -> CGFloat {
    max(chromeBottom - safeAreaBottom, voiceControlHeight) + 8
}

@MainActor
final class GuideMapController: UIViewController, NavigationMapViewDelegate {
    private static let log = Logger(subsystem: "MapboxNavigationAgent", category: "map")
    private let guide: Guide
    private let navigationMapView: NavigationMapView
    private var placesManager: PointAnnotationManager?
    private var displayedPlaceIDs: [String] = []
    private var displayedSelection: String?
    private var subscriptions: Set<AnyCancellable> = []
    private var styleObservers: Set<AnyCancelable> = []
    private var displayedOverlay: ToolOverlay?
    private var chrome = Chrome.browsing
    /// Camera padding from the bottom edge of the map. Ornament margins are derived from it.
    private var chromeBottom: CGFloat = 96
    private var voiceControlHeight: CGFloat = 0
    var onSelect: ((Place) -> Void)?

    private enum Chrome {
        case browsing
        case place
        case navigating
    }

    init(guide: Guide) {
        self.guide = guide
        navigationMapView = NavigationMapView(
            location: guide.$currentLocation.compactMap { $0 }.eraseToAnyPublisher(),
            routeProgress: guide.$routeProgress.eraseToAnyPublisher(),
            routeRefreshing: guide.routeRefreshing.eraseToAnyPublisher(),
            predictiveCacheManager: guide.predictiveCacheManager
        )
        super.init(nibName: nil, bundle: nil)
        navigationMapView.mapView.mapboxMap.loadStyle(.streets)
        applyChrome(.browsing)
        navigationMapView.delegate = self
        navigationMapView.showsTrafficOnRouteLine = true
        observeRoutes()
        // A style load drops runtime sources and layers, so the overlay is redrawn after each one.
        navigationMapView.mapView.mapboxMap.onStyleLoaded.observe { [weak self] _ in
            self?.drawOverlay()
        }
        .store(in: &styleObservers)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        view = navigationMapView
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        navigationMapView.mapView.mapboxMap.setCamera(to: CameraOptions(center: Guide.origin.coordinate, zoom: 14))
        guide.cameraState = .following
        guide.start()
    }

    func setVoiceControlHeight(_ height: CGFloat) {
        guard height != voiceControlHeight else { return }
        voiceControlHeight = height
        updateOrnaments()
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        updateOrnaments()
    }

    func updateChrome(isNavigating: Bool, hasSelection: Bool, places: [Place]) {
        let next: Chrome = isNavigating ? .navigating : (hasSelection ? .place : .browsing)
        let leftNavigation = chrome == .navigating && next != .navigating
        guard next != chrome else { return }
        applyChrome(next)
        // Results that arrived during the trip were not framed, because the camera was following the route.
        if leftNavigation, !hasSelection, !places.isEmpty {
            frame(places)
        }
    }

    func setPlaces(_ places: [Place], selectedID: String?) {
        let ids = places.map(\.id)
        let resultsChanged = ids != displayedPlaceIDs
        guard resultsChanged || selectedID != displayedSelection else { return }
        displayedPlaceIDs = ids
        displayedSelection = selectedID

        let manager = placesManager ?? navigationMapView.mapView.annotations.makePointAnnotationManager(id: "places")
        placesManager = manager
        let regular = pinImage(selected: false)
        let selected = pinImage(selected: true)
        manager.annotations = places.map { place in
            var annotation = PointAnnotation(
                id: place.id,
                coordinate: CLLocationCoordinate2D(
                    latitude: place.coordinate.latitude,
                    longitude: place.coordinate.longitude
                )
            )
            annotation.image = place.id == selectedID ? selected : regular
            annotation.iconAnchor = .bottom
            annotation.tapHandler = { [weak self] _ in
                self?.onSelect?(place)
                return true
            }
            return annotation
        }
        if resultsChanged, !places.isEmpty, !guide.isNavigating {
            frame(places)
        }
    }

    func setOverlay(_ overlay: ToolOverlay?) {
        guard overlay != displayedOverlay else { return }
        displayedOverlay = overlay
        drawOverlay()
        if let overlay, !guide.isNavigating {
            frame(overlay.coordinates)
        }
    }

    private func drawOverlay() {
        guard let map = navigationMapView.mapView.mapboxMap, map.isStyleLoaded else { return }
        do {
            if !map.sourceExists(withId: Overlay.source) {
                try addOverlayLayers(to: map)
            }
            let features = (displayedOverlay?.shapes ?? []).map(\.feature)
            map.updateGeoJSONSource(withId: Overlay.source, geoJSON: .featureCollection(FeatureCollection(features: features)))
        } catch {
            Self.log.error("Could not draw the tool overlay: \(error.localizedDescription, privacy: .public)")
        }
    }

    private func addOverlayLayers(to map: MapboxMap) throws {
        var source = GeoJSONSource(id: Overlay.source)
        source.data = .featureCollection(FeatureCollection(features: []))
        try map.addSource(source)

        let color = StyleColor(.systemIndigo)
        var fill = FillLayer(id: Overlay.fill, source: Overlay.source)
        fill.filter = Exp(.eq) { Exp(.geometryType); "Polygon" }
        fill.fillColor = .constant(color)
        fill.fillOpacity = .constant(0.2)
        try map.addLayer(fill)

        var line = LineLayer(id: Overlay.line, source: Overlay.source)
        line.filter = Exp(.neq) { Exp(.geometryType); "Point" }
        line.lineColor = .constant(color)
        line.lineWidth = .constant(3)
        line.lineJoin = .constant(.round)
        line.lineCap = .constant(.round)
        try map.addLayer(line)

        var circle = CircleLayer(id: Overlay.circle, source: Overlay.source)
        circle.filter = Exp(.eq) { Exp(.geometryType); "Point" }
        circle.circleColor = .constant(color)
        // A labeled point, such as a numbered stop, needs room for its label.
        circle.circleRadius = .expression(Exp(.switchCase) {
            Exp(.has) { "label" }
            11
            7
        })
        circle.circleStrokeColor = .constant(StyleColor(.white))
        circle.circleStrokeWidth = .constant(2)
        try map.addLayer(circle)

        var label = SymbolLayer(id: Overlay.label, source: Overlay.source)
        label.filter = Exp(.has) { "label" }
        label.textField = .expression(Exp(.get) { "label" })
        label.textColor = .constant(StyleColor(.white))
        label.textSize = .constant(12)
        label.textFont = .constant(["DIN Pro Bold", "Arial Unicode MS Bold"])
        label.textAllowOverlap = .constant(true)
        label.textIgnorePlacement = .constant(true)
        try map.addLayer(label)
    }

    private enum Overlay {
        static let source = "tool-overlay"
        static let fill = "tool-overlay-fill"
        static let line = "tool-overlay-line"
        static let circle = "tool-overlay-circle"
        static let label = "tool-overlay-label"
    }

    /// Fits every result pin in the visible map once a query's places arrive.
    private func frame(_ places: [Place]) {
        frame(places.map(\.coordinate))
    }

    /// Fits the coordinates in the visible map, unless the camera is following a trip.
    private func frame(_ coordinates: [Coordinate]) {
        let coordinates = coordinates.map {
            CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
        }
        guard !coordinates.isEmpty else { return }
        guide.cameraState = .idle
        navigationMapView.navigationCamera.stop()
        do {
            let camera = try navigationMapView.mapView.mapboxMap.camera(
                for: coordinates,
                camera: CameraOptions(padding: navigationMapView.viewportPadding, bearing: 0, pitch: 0),
                coordinatesPadding: UIEdgeInsets(top: 64, left: 40, bottom: 40, right: 40),
                maxZoom: 15,
                offset: nil
            )
            navigationMapView.mapView.camera.ease(to: camera, duration: 0.8)
        } catch {
            return
        }
    }

    func navigationMapView(_ navigationMapView: NavigationMapView, didSelect alternativeRoute: AlternativeRoute) {
        Task { await guide.selectAlternative(alternativeRoute) }
    }

    func navigationMapViewUserDidStartInteraction(_ navigationMapView: NavigationMapView) {
        if guide.cameraState != .idle {
            guide.cameraState = .idle
        }
    }

    private func observeRoutes() {
        guide.$preview
            .combineLatest(guide.$activeRoutes)
            .sink { [weak self] preview, active in
                guard let self else { return }
                if let active {
                    navigationMapView.show(active, routeAnnotationKinds: [.relativeDurationsOnAlternativeManuever])
                } else if let preview {
                    navigationMapView.showcase(preview, routeAnnotationKinds: [.routeDurations], animated: true)
                } else {
                    navigationMapView.removeRoutes()
                }
            }
            .store(in: &subscriptions)

        guide.$cameraState
            .sink { [weak self] state in
                self?.navigationMapView.update(navigationCameraState: state)
            }
            .store(in: &subscriptions)
    }

    private func applyChrome(_ chrome: Chrome) {
        self.chrome = chrome
        let insets: UIEdgeInsets
        switch chrome {
        case .browsing:
            insets = UIEdgeInsets(top: 100, left: 32, bottom: 96, right: 32)
        case .place:
            insets = UIEdgeInsets(top: 100, left: 32, bottom: 360, right: 32)
        case .navigating:
            insets = UIEdgeInsets(top: 160, left: 32, bottom: 180, right: 32)
        }
        navigationMapView.viewportPadding = insets
        chromeBottom = insets.bottom
        updateOrnaments()
    }

    /// Keeps the logo and info button above the voice control, and above a place card or the guidance bar.
    private func updateOrnaments() {
        let margin = CGPoint(
            x: 8,
            y: ornamentBottomMargin(
                chromeBottom: chromeBottom,
                safeAreaBottom: navigationMapView.safeAreaInsets.bottom,
                voiceControlHeight: voiceControlHeight
            )
        )
        guard navigationMapView.mapView.ornaments.options.logo.margins != margin else { return }
        navigationMapView.mapView.ornaments.options.logo.margins = margin
        navigationMapView.mapView.ornaments.options.attributionButton.margins = margin
    }

    private func pinImage(selected: Bool) -> PointAnnotation.Image {
        let configuration = UIImage.SymbolConfiguration(pointSize: selected ? 34 : 26, weight: .semibold)
        let image = UIImage(systemName: "mappin.circle.fill", withConfiguration: configuration)!
            .withTintColor(selected ? .systemBlue : .systemRed, renderingMode: .alwaysOriginal)
        return PointAnnotation.Image(image: image, name: selected ? "place-pin-selected" : "place-pin")
    }
}

private extension ToolShape {
    var feature: Feature {
        var feature = Feature(geometry: geometry)
        if case .point(_, let label?) = self {
            feature.properties = ["label": .string(label)]
        }
        return feature
    }

    var geometry: Geometry {
        switch self {
        case .point(let coordinate, _):
            .point(Point(coordinate.location))
        case .line(let coordinates):
            .lineString(LineString(coordinates.map(\.location)))
        case .polygon(let rings):
            .polygon(Polygon(rings.map { $0.map(\.location) }))
        }
    }
}

private extension Coordinate {
    var location: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}
