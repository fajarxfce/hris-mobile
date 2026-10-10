import FirebaseCore
import FirebaseCrashlytics
import Foundation
import StarterKit

final class FirebaseCrashClient: NSObject, AppleCrashClient {
    func recordDiagnostic(json: String, failure: Bool) {
        guard FirebaseApp.app() != nil else { return }
        let client = Crashlytics.crashlytics()
        client.log(json)
        if failure {
            client.record(error: NSError(domain: "StarterDiagnostic", code: 1,
                userInfo: [NSLocalizedDescriptionKey: json]))
        }
    }
}
