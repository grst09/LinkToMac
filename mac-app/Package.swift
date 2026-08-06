// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "LinkToMac",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "LinkToMac",
            path: "Sources/LinkToMac"
        )
    ]
)
