// swift-tools-version: 6.1
import Foundation
import PackageDescription

let sdkPath = ProcessInfo.processInfo.environment["MATRIX_OBSERVER_SDK_PATH"]!
let package = Package(
    name: "MatrixObserverProof",
    platforms: [.macOS(.v12), .iOS(.v16)],
    dependencies: [.package(name: "MatrixRustSDK", path: sdkPath)],
    targets: [
        .testTarget(
            name: "MatrixObserverProofTests",
            dependencies: [.product(name: "MatrixRustSDK", package: "MatrixRustSDK")]
        )
    ]
)
