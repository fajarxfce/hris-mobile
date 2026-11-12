// swift-tools-version: 6.1
import PackageDescription

// The build driver stages this manifest beside the freshly built, verified SDK package.
let package = Package(
    name: "HrisCollaborationAppleProof",
    platforms: [.iOS(.v16), .macOS(.v12)],
    products: [.library(name: "HrisCollaborationAppleProof", targets: ["HrisCollaborationAppleProof"])],
    dependencies: [.package(name: "MatrixRustSDK", path: "../sdk")],
    targets: [
        .target(
            name: "HrisCollaborationAppleProof",
            dependencies: [.product(name: "MatrixRustSDK", package: "MatrixRustSDK")]
        ),
        .testTarget(
            name: "HrisCollaborationAppleProofTests",
            dependencies: ["HrisCollaborationAppleProof", .product(name: "MatrixRustSDK", package: "MatrixRustSDK")]
        )
    ]
)
