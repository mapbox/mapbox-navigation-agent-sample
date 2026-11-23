import MapboxDirections
import MapboxNavigationCore
import SwiftUI

struct MainView: View {
    @StateObject private var guide = Guide()
    @StateObject private var voice = MainViewModel()
    @State private var service = PlacesService()
    @State private var places: [Place] = []
    /// Shapes from the latest geometry result. The next one replaces it, and starting a trip clears it.
    @State private var overlay: ToolOverlay?
    @State private var selected: Place?
    @State private var details: PlaceDetails?
    @State private var mode: TravelMode = .walking
    /// Set while a `directions_tool` result should start the trip once its route exists.
    @State private var guidanceRequest: RouteRequest?
    /// Bumps when a spoken route arrives, so the same destination starts guidance again.
    @State private var routeGeneration = 0
    /// Measured height of the voice control, so the map logo clears it.
    @State private var voiceControlHeight: CGFloat = 0

    var body: some View {
        NavigationStack {
            ZStack {
                GuideMapView(
                    guide: guide,
                    places: places,
                    overlay: overlay,
                    selectedPlaceID: selected?.id,
                    voiceControlHeight: voiceControlHeight
                ) { place in
                    select(place)
                }
                .ignoresSafeArea()

                if guide.isNavigating {
                    guidance
                } else {
                    browsing
                }

                ZStack(alignment: .bottom) {
                    Color.clear
                        .allowsHitTesting(false)
                    VoiceControl(voice: voice)
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { voiceControlHeight = $0 }
                }

                // During a trip, guidance shows the banner below the turn instruction instead.
                if let notice = voice.notice, !guide.isNavigating {
                    NoticeBanner(notice: notice, dismiss: voice.dismissNotice)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .animation(.easeInOut(duration: 0.25), value: voice.notice)
            .toolbarBackground(.hidden, for: .navigationBar)
            .onAppear {
                // Each result replaces the last one, so new pins clear an old overlay and a new overlay clears old pins.
                voice.onPlaces = { found in
                    guidanceRequest = nil
                    overlay = nil
                    places = found
                    selected = nil
                    guide.clearPreview()
                }
                voice.onOverlay = { found in
                    places = []
                    selected = nil
                    overlay = found
                }
                voice.onRoute = { route in
                    overlay = nil
                    routeGeneration += 1
                    guidanceRequest = route
                    show(route)
                }
                voice.onStopNavigation = {
                    // Unlike the End button, which returns to the destination's route preview, a spoken stop
                    // means the user is done with the trip, so deselect the destination and clear the preview.
                    guidanceRequest = nil
                    selected = nil
                    guide.stopNavigation()
                }
            }
            .task { await voice.requestPermissions() }
        }
        .task(id: selected?.id) {
            guard let selected else {
                details = nil
                return
            }
            details = try? await service.details(for: selected)
        }
        .task(id: routeToken) {
            guard let selected else {
                guidanceRequest = nil
                guide.clearPreview()
                return
            }
            if guidanceRequest != nil {
                let started = await guide.followRoute(to: selected, mode: mode)
                if started, !Task.isCancelled {
                    guidanceRequest = nil
                }
            } else {
                await guide.previewRoute(to: selected, mode: mode)
            }
        }
        .onChange(of: guide.isNavigating) { _, navigating in
            // End returns to the place that was being navigated. Arrival returns to the start.
            guard !navigating, !guide.endedByArrival, let selected else { return }
            Task { await guide.previewRoute(to: selected, mode: mode) }
        }
        .onChange(of: guide.arrivalCount) { _, _ in
            guidanceRequest = nil
            selected = nil
        }
        .onChange(of: voiceContextRevision) { _, _ in
            voice.updateContext(currentVoiceContext)
        }
    }

    private var routeToken: String {
        guard let selected else { return "" }
        return "\(selected.id)|\(mode.rawValue)|\(routeGeneration)"
    }

    /// Changes when the trip, its destination, or the travel mode changes, and again when the session can accept context.
    private var voiceContextRevision: String {
        "\(guide.isNavigating)|\(selected?.name ?? "")|\(mode.rawValue)|\(voice.phase)"
    }

    private var currentVoiceContext: VoiceContext {
        let active = guide.isNavigating
        let destination = selected?.name ?? "none"
        let travelMode = (active || selected != nil) ? modeToken : "none"
        let origin = Guide.origin
        let summary = "Navigation: \(active ? "active" : "idle"). Destination: \(destination). Mode: \(travelMode)."
        return VoiceContext(
            latitude: origin.coordinate.latitude,
            longitude: origin.coordinate.longitude,
            navigationActive: active,
            summary: summary
        )
    }

    private var modeToken: String {
        switch mode {
        case .walking: "walking"
        case .driving: "driving"
        case .cycling: "cycling"
        }
    }

    private var browsing: some View {
        VStack {
            Spacer()
            if let selected {
                placeCard(selected)
            } else if let url = voice.mapImageURL {
                mapImageCard(url)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 72)
    }

    /// The latest `static_map_image_tool` image. A place card takes its spot while a place is selected.
    private func mapImageCard(_ url: URL) -> some View {
        AsyncImage(url: url) { phase in
            switch phase {
            case .success(let image):
                image.resizable().aspectRatio(contentMode: .fit)
            case .failure:
                Label("The map image didn't load.", systemImage: "photo")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 120)
            default:
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 120)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(alignment: .topTrailing) {
            Button {
                voice.dismissMapImage()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title2)
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, .black.opacity(0.5))
            }
            .padding(8)
            .accessibilityLabel("Close map image")
        }
        .padding(8)
        .background(.thickMaterial, in: RoundedRectangle(cornerRadius: 22))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Map image")
    }

    private func placeCard(_ place: Place) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(place.name)
                        .font(.title3.weight(.semibold))
                        .lineLimit(2)
                    if let category = place.category {
                        Text(category)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    if !place.address.isEmpty {
                        Text(place.address)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                }
                Spacer(minLength: 8)
                Button(action: dismissPlace) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundStyle(.secondary)
                        .frame(width: 44, height: 44)
                        .contentShape(Circle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Close")
            }

            if let photo = details?.photo {
                AsyncImage(url: photo) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Rectangle().fill(.quaternary)
                }
                .frame(height: 140)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            }

            Picker("Travel mode", selection: $mode) {
                ForEach(TravelMode.allCases) { mode in
                    Text(mode.rawValue).tag(mode)
                }
            }
            .pickerStyle(.segmented)

            routeSummary

            Button {
                guide.startNavigation()
            } label: {
                Text("Start")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .disabled(guide.preview == nil || guide.isCalculatingRoute)

            contact
        }
        .padding(16)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
        .contentShape(RoundedRectangle(cornerRadius: 20))
    }

    private func dismissPlace() {
        selected = nil
        guide.clearPreview()
        guide.cameraState = .following
    }

    /// Selects the destination and matches the travel mode, which previews the route the same way a pin tap does.
    private func show(_ route: RouteRequest) {
        switch route.profile {
        case .walking: mode = .walking
        case .driving: mode = .driving
        case .cycling: mode = .cycling
        }
        let destination = route.destination
        if let match = places.first(where: { $0.coordinate.distance(to: destination.coordinate) < 100 }) {
            selected = match
            return
        }
        if !places.contains(where: { $0.id == destination.id }) {
            places.append(destination)
        }
        selected = destination
    }

    @ViewBuilder private var routeSummary: some View {
        if guide.isCalculatingRoute {
            ProgressView("Finding a route")
                .frame(maxWidth: .infinity, alignment: .leading)
        } else if let route = guide.preview?.mainRoute.route {
            HStack {
                Text(Duration.seconds(route.expectedTravelTime), format: .units(allowed: [.hours, .minutes], width: .wide))
                    .font(.headline)
                Spacer()
                Text(Measurement(value: route.distance, unit: UnitLength.meters),
                     format: .measurement(width: .abbreviated, usage: .road))
                    .foregroundStyle(.secondary)
            }
        } else if let routeError = guide.routeError {
            Text(routeError)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var contact: some View {
        if let details, details.phone != nil || details.website != nil || !details.hours.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                if let today = details.hours.first(where: { $0.day == Self.today }) {
                    Label("\(Self.dayName(today.day)) \(today.hours)", systemImage: "clock")
                }
                if let phone = details.phone, let url = URL(string: "tel:\(phone)") {
                    Link(destination: url) { Label(phone, systemImage: "phone") }
                }
                if let website = details.website {
                    Link(destination: website) {
                        Label(website.host() ?? website.absoluteString, systemImage: "safari")
                    }
                }
            }
            .font(.subheadline)
        }
    }

    private var guidance: some View {
        VStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                if let distance = guide.routeProgress?.currentLegProgress.currentStepProgress.distanceRemaining {
                    Text(Measurement(value: distance, unit: UnitLength.meters),
                         format: .measurement(width: .abbreviated, usage: .road))
                        .font(.title.weight(.semibold))
                }
                if let text = guide.visualInstruction?.primaryInstruction.text {
                    Text(text)
                        .font(.title3)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))

            // Below the turn instruction, so the driver can always read it. Tool names are left out during a trip.
            if let notice = voice.notice, !notice.isToolName {
                NoticeBanner(notice: notice, dismiss: voice.dismissNotice)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }

            Spacer()

            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    if let duration = guide.routeProgress?.currentLegProgress.durationRemaining {
                        Text(Date().addingTimeInterval(duration), format: .dateTime.hour().minute())
                            .font(.title3.weight(.semibold))
                        Text(Duration.seconds(duration), format: .units(allowed: [.hours, .minutes], width: .wide))
                            .foregroundStyle(.secondary)
                    }
                    if let distance = guide.routeProgress?.currentLegProgress.distanceRemaining {
                        Text(Measurement(value: distance, unit: UnitLength.meters),
                             format: .measurement(width: .abbreviated, usage: .road))
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Button {
                    guide.cameraState = guide.cameraState == .following ? .overview : .following
                } label: {
                    Image(systemName: guide.cameraState == .following ? "location.north.line.fill" : "location.fill")
                        .font(.title2)
                        .frame(width: 52, height: 52)
                }
                .buttonStyle(.plain)
                Button {
                    guide.stopNavigation()
                } label: {
                    Text("End")
                        .font(.headline)
                        .padding(.horizontal, 8)
                        .frame(height: 52)
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
            }
            .padding(16)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 72)
    }

    private func select(_ place: Place) {
        guidanceRequest = nil
        selected = place
    }

    private static let dayNames = ["Su": "Sunday", "Mo": "Monday", "Tu": "Tuesday", "We": "Wednesday",
                                   "Th": "Thursday", "Fr": "Friday", "Sa": "Saturday"]

    private static var today: String {
        ["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"][Calendar.current.component(.weekday, from: .now) - 1]
    }

    private static func dayName(_ day: String) -> String {
        dayNames[day] ?? day
    }
}
