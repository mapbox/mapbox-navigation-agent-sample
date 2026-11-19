import Combine
import CoreLocation
import MapboxDirections
import MapboxNavigationCore

/// Turn-by-turn session for the sample app. Location is simulated from the Ferry Building
/// so a route can be previewed and followed on the simulator.
@MainActor
final class Guide: ObservableObject {
    let predictiveCacheManager: PredictiveCacheManager?

    @Published private(set) var isNavigating = false
    /// True after the traveler arrives, until the next trip starts or the user ends one.
    /// The screen uses this to return to the start instead of previewing the destination again.
    @Published private(set) var endedByArrival = false
    /// Bumps each time a trip arrives, so the screen can drop the guidance interface.
    @Published private(set) var arrivalCount = 0
    @Published private(set) var preview: NavigationRoutes?
    @Published private(set) var activeRoutes: NavigationRoutes?
    @Published private(set) var visualInstruction: VisualInstructionBanner?
    @Published private(set) var routeProgress: RouteProgress?
    @Published private(set) var currentLocation: CLLocation?
    @Published var cameraState: NavigationCameraState = .following
    @Published private(set) var isCalculatingRoute = false
    @Published private(set) var routeError: String?

    private let speechArbiter = GuidanceSpeechArbiter()
    private let core: MapboxNavigation
    private let voiceController: RouteVoiceController
    /// Bumped when guidance is stopped, so a route still being calculated does not start the trip.
    private var followGeneration = 0
    private var cancellables = Set<AnyCancellable>()
    private var isCompletingArrival = false

    var routeRefreshing: AnyPublisher<RefreshingStatus, Never> { core.navigation().routeRefreshing }

    /// An absolute cache path. The SDK treats a missing path as the process working directory, which is `/` on device.
    private static var tileStoreURL: URL {
        let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("mapbox-tiles", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// The simulated start used before the first location update arrives.
    static let origin = CLLocation(
        latitude: PlacesService.origin.latitude,
        longitude: PlacesService.origin.longitude
    )

    init() {
        let token = Bundle.main.object(forInfoDictionaryKey: "MBXAccessToken") as? String ?? ""
        let navigationProvider = MapboxNavigationProvider(coreConfig: CoreConfig(
            credentials: .init(accessToken: token),
            locationSource: .simulation(initialLocation: Self.origin),
            tilestoreConfig: .custom(Self.tileStoreURL),
            ttsConfig: .custom(speechSynthesizer: speechArbiter)
        ))
        // The real synthesizer needs the provider's billing token, which does not exist until the
        // provider is built. Guidance is not spoken before this returns. The token closure is
        // Sendable; the provider itself is not.
        let skuTokens = navigationProvider.skuTokenProvider
        speechArbiter.attach(delegate: MultiplexedSpeechSynthesizer(
            mapboxSpeechApiConfiguration: navigationProvider.coreConfig.credentials.speech,
            skuTokenProvider: skuTokens.skuToken
        ))
        core = navigationProvider.mapboxNavigation
        voiceController = navigationProvider.routeVoiceController
        // The assistant holds a play-and-record session. Guidance would replace it with
        // playback for each maneuver, and that stops the microphone tap. The voice floor
        // pauses the reply instead, and the maneuver speaks through the session the assistant opened.
        voiceController.speechSynthesizer.managesAudioSession = false
        voiceController.playsRerouteSound = false
        predictiveCacheManager = navigationProvider.predictiveCacheManager

        core.tripSession().session
            .map { if case .activeGuidance = $0.state { true } else { false } }
            .removeDuplicates()
            .assign(to: &$isNavigating)
        // Arrival stays in active guidance until something leaves it, which kept the maneuver cards up.
        core.tripSession().session
            .map(\.state)
            .removeDuplicates()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] state in
                guard case .activeGuidance(.complete) = state else { return }
                self?.completeNavigation()
            }
            .store(in: &cancellables)
        core.navigation().bannerInstructions
            .map(\.visualInstruction)
            .assign(to: &$visualInstruction)
        core.navigation().routeProgress
            .map(\.?.routeProgress)
            .assign(to: &$routeProgress)
        core.tripSession().navigationRoutes
            .assign(to: &$activeRoutes)
        core.navigation().locationMatching
            .map(\.enhancedLocation)
            .assign(to: &$currentLocation)
    }

    func start() {
        core.tripSession().startFreeDrive()
    }

    func clearPreview() {
        preview = nil
        routeError = nil
    }

    @discardableResult
    func previewRoute(to place: Place, mode: TravelMode) async -> Bool {
        isCalculatingRoute = true
        routeError = nil
        let request = core.routingProvider().calculateRoutes(options: routeOptions(to: place, mode: mode))
        defer { isCalculatingRoute = false }
        do {
            let routes = try await withTaskCancellationHandler {
                try await request.value
            } onCancel: {
                request.cancel()
            }
            guard !Task.isCancelled else { return false }
            preview = routes
            cameraState = .idle
            return true
        } catch is CancellationError {
            return false
        } catch {
            guard !Task.isCancelled else { return false }
            preview = nil
            routeError = error.localizedDescription
            return false
        }
    }

    /// Calculates the route with the Navigation SDK, then starts the trip session.
    func followRoute(to place: Place, mode: TravelMode) async -> Bool {
        let generation = followGeneration
        guard await previewRoute(to: place, mode: mode), !Task.isCancelled, generation == followGeneration else { return false }
        startNavigation()
        return true
    }

    func startNavigation() {
        guard let preview else { return }
        endedByArrival = false
        isCompletingArrival = false
        core.tripSession().startActiveGuidance(with: preview, startLegIndex: 0)
        cameraState = .following
        self.preview = nil
    }

    func stopNavigation() {
        followGeneration += 1
        endedByArrival = false
        isCompletingArrival = false
        speechArbiter.interruptSpeaking()
        core.tripSession().startFreeDrive()
        cameraState = .following
        preview = nil
    }

    /// The traveler reached the destination. Leave guidance without cutting off the arrival prompt.
    private func completeNavigation() {
        guard !isCompletingArrival else { return }
        isCompletingArrival = true
        endedByArrival = true
        arrivalCount += 1
        followGeneration += 1
        core.tripSession().startFreeDrive()
        cameraState = .following
        preview = nil
    }

    func selectAlternative(_ alternative: AlternativeRoute) async {
        if let preview {
            self.preview = await preview.selecting(alternativeRoute: alternative)
        } else {
            core.navigation().selectAlternativeRoute(with: alternative.routeId)
        }
    }

    private func routeOptions(to place: Place, mode: TravelMode) -> NavigationRouteOptions {
        let origin = currentLocation ?? Self.origin
        var start = Waypoint(location: origin, name: "Start")
        if origin.course >= 0 {
            start.heading = origin.course
            start.headingAccuracy = 90
        }
        let destination = Waypoint(
            coordinate: CLLocationCoordinate2D(
                latitude: place.coordinate.latitude,
                longitude: place.coordinate.longitude
            ),
            name: place.name
        )
        return NavigationRouteOptions(waypoints: [start, destination], profileIdentifier: mode.profile)
    }
}

extension TravelMode {
    var profile: ProfileIdentifier {
        switch self {
        case .walking: .walking
        case .driving: .automobileAvoidingTraffic
        case .cycling: .cycling
        }
    }
}
