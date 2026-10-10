import SwiftUI
import StarterKit

struct ComposeView: UIViewControllerRepresentable {
    let host: AppleAppHost

    func makeUIViewController(context: Context) -> UIViewController { host.viewController() }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
