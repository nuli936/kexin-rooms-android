// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "KexinProtocolTests",
    platforms: [.macOS(.v13)],
    dependencies: [.package(url: "https://github.com/scinfu/SwiftSoup.git", from: "2.13.9")],
    targets: [
        .target(name: "SchoolProtocol", dependencies: [.product(name: "SwiftSoup", package: "SwiftSoup")],
                path: "KexinRooms", exclude: ["ContentView.swift", "CredentialStore.swift", "KexinRoomsApp.swift", "RoomsModel.swift", "SchoolClient.swift"]),
        .testTarget(name: "SchoolProtocolTests", dependencies: ["SchoolProtocol"], path: "Tests")
    ]
)
