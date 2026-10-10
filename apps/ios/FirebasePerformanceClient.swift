import FirebaseCore
import FirebasePerformance
import Foundation
import StarterKit

final class FirebasePerformanceClient: NSObject, ApplePerformanceClient {
    func start(name: String) -> (any ApplePerformanceSample)? {
        #if DEBUG
        return nil
        #else
        guard FirebaseApp.app() != nil, let trace = Performance.startTrace(name: name) else { return nil }
        return FirebasePerformanceSample(trace: trace)
        #endif
    }
}

private final class FirebasePerformanceSample: NSObject, ApplePerformanceSample {
    private var trace: Trace?
    init(trace: Trace) { self.trace = trace }
    func finish(outcome: String) {
        guard let trace else { return }
        defer { trace.stop(); self.trace = nil }
        trace.setValue(outcome, forAttribute: "outcome")
    }
}
