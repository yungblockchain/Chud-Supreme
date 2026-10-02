// swift-tools-version:5.9
// CHUD STREAMS for Mac (macOS 13 Ventura or later). GitHub builds it; see README.md.
import PackageDescription

let package = Package(
    name: "ChudStreams",
    platforms: [.macOS(.v13)],
    dependencies: [
        // mpv with FFmpeg and libass, as static libraries: plays MKV, AVI, MPEG-TS, HLS, HEVC,
        // AV1 and styled ASS/SSA subtitles inside the app. LGPL build.
        .package(url: "https://github.com/mpvkit/MPVKit", exact: "1.0.0"),
    ],
    targets: [
        .executableTarget(
            name: "ChudStreams",
            dependencies: [
                .product(name: "MPVKit", package: "MPVKit"),
            ],
            path: "Sources/ChudStreams",
            linkerSettings: [
                .linkedFramework("OpenGL"),
            ]
        )
    ]
)
