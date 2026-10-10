import Foundation
import FirebaseCore
import FirebaseRemoteConfig
import StarterKit

final class FirebaseFeatureFlagClient: NSObject, AppleRemoteConfigClient {
    var configured: Bool { FirebaseApp.app() != nil }

    func fetch(completion: @escaping ([String: String]?, Error?) -> Void) {
        guard configured else { completion(nil, NSError(domain: "FirebaseConfiguration", code: 1)); return }
        let client = RemoteConfig.remoteConfig()
        let settings = RemoteConfigSettings()
        settings.fetchTimeout = 15
        client.configSettings = settings
        client.fetch(withExpirationDuration: 0) { status, error in
            guard status == .success else {
                completion(nil, error ?? NSError(domain: "FirebaseRemoteConfig", code: 1))
                return
            }
            client.activate { _, error in
                if let error { completion(nil, error); return }
                let values = Dictionary(uniqueKeysWithValues: client.allKeys(from: .remote).map {
                    ($0, client.configValue(forKey: $0).stringValue)
                })
                completion(values, nil)
            }
        }
    }
}
