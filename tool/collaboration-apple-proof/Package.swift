// swift-tools-version: 6.1
import PackageDescription

let package = Package(
    name: "HrisCollaborationAppleProof",
    platforms: [.iOS(.v16), .macOS(.v12)],
    products: [.library(name: "HrisCollaborationAppleProof", targets: ["HrisCollaborationAppleProof"])],
    dependencies: [
        .package(url: "https://github.com/matrix-org/matrix-rust-components-swift.git", exact: "26.10.27")
    ],
    targets: [
        .target(
            name: "HrisCollaborationAppleProof",
            dependencies: [.product(name: "MatrixRustSDK", package: "matrix-rust-components-swift")]
        ),
        .testTarget(name: "HrisCollaborationAppleProofTests", dependencies: ["HrisCollaborationAppleProof"])
    ]
)
