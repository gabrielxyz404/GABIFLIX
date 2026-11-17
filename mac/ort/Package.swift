// swift-tools-version:5.9
// Só serve para o SwiftPM baixar o ONNX Runtime (xcframework oficial da Microsoft, mesma versão da prova do motor de áudio).
// O app NÃO usa SwiftPM: o build.sh copia o framework de .build/artifacts e compila com o swiftc de sempre.
import PackageDescription

let package = Package(
    name: "ortfetch",
    platforms: [.macOS(.v12)],
    dependencies: [.package(url: "https://github.com/microsoft/onnxruntime-swift-package-manager", exact: "1.20.0")],
    targets: [.executableTarget(name: "ortfetch", dependencies: [.product(name: "onnxruntime", package: "onnxruntime-swift-package-manager")])]
)
