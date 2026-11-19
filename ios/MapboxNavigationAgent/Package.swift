// swift-tools-version: 6.4

import PackageDescription

let package = Package(
    name: "MapboxNavigationAgent",
    platforms: [
        .iOS(.v15),
    ],
    products: [
        .library(
            name: "MapboxNavigationAgent",
            targets: ["MapboxNavigationAgent"]
        ),
    ],
    targets: [
        .target(
            name: "MapboxNavigationAgent",
            dependencies: ["ViaKit"]
        ),
        .binaryTarget(
            name: "ViaKit",
            url: "https://api.mapbox.com/downloads/v2/mapbox-navigation-agent-ios/releases/ios/packages/0.1.0-alpha.4/ViaKit.xcframework.zip",
            checksum: "18bd558997979845282e78da0b5fe7743de425a7a98baef86ad6a5a10c9c2e95"
        ),
    ],
    swiftLanguageModes: [.v6]
)
