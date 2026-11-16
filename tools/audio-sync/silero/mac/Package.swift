// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "sileroprobe",
    platforms: [.macOS(.v12)],
    dependencies: [.package(url: "https://github.com/microsoft/onnxruntime-swift-package-manager", exact: "1.20.0")],
    targets: [.executableTarget(name: "sileroprobe", dependencies: [.product(name: "onnxruntime", package: "onnxruntime-swift-package-manager")])]
)
