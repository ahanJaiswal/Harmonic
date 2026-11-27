// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "HarmonicDSP",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "HarmonicDSP", targets: ["HarmonicDSP"]),
    ],
    targets: [
        .target(name: "HarmonicDSP"),
        .testTarget(
            name: "HarmonicDSPTests",
            dependencies: ["HarmonicDSP"],
            resources: [.process("Golden")]
        ),
    ]
)
