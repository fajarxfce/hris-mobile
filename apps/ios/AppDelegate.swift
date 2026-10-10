import UIKit
import BackgroundTasks
import UserNotifications
import FirebaseCore
import FirebaseCrashlytics
import FirebasePerformance
import FirebaseMessaging
import StarterKit

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate, AppleFirebaseClient {
    lazy var host = AppleAppHost(firebase: self, remoteConfig: FirebaseFeatureFlagClient(), credentials: KeychainClient(), crash: FirebaseCrashClient(), performance: FirebasePerformanceClient())
    private let syncIdentifier = Bundle.main.bundleIdentifier! + ".sync"
    var configured: Bool { FirebaseApp.app() != nil }

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: syncIdentifier, using: .main) { [weak self] task in
            guard let self else { task.setTaskCompleted(success: false); return }
            self.scheduleSync()
            let execution = self.host.runBackgroundSync { success in
                task.setTaskCompleted(success: success.boolValue)
            }
            task.expirationHandler = { execution.cancel() }
        }
        UNUserNotificationCenter.current().delegate = self
        if Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist") != nil {
            Performance.sharedInstance().isInstrumentationEnabled = false
            FirebaseApp.configure()
            #if DEBUG
            Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(false)
            Performance.sharedInstance().isDataCollectionEnabled = false
            #else
            Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(true)
            Performance.sharedInstance().isDataCollectionEnabled = true
            #endif
            Messaging.messaging().delegate = self
            application.registerForRemoteNotifications()
        }
        return true
    }

    func fetchToken(completion: @escaping (String?, Error?) -> Void) {
        guard configured else { completion(nil, NSError(domain: "FirebaseConfiguration", code: 1)); return }
        Messaging.messaging().token { token, error in
            completion(token, error as NSError?)
        }
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        if configured { Messaging.messaging().apnsToken = deviceToken }
    }

    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        if let token = fcmToken { host.receivedToken(token: token) }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        if notification.request.trigger is UNPushNotificationTrigger {
            let content = notification.request.content
            host.receivedNotification(
                id: content.userInfo["gcm.message_id"] as? String ?? notification.request.identifier,
                title: content.title, body: content.body,
                destination: content.userInfo["destination"] as? String ?? "inbox"
            ) { completionHandler([.banner, .sound, .list]) }
        } else { completionHandler([.banner, .sound, .list]) }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let content = response.notification.request.content
        let destination = content.userInfo["destination"] as? String ?? "inbox"
        host.openNotification(destination: destination)
        if response.notification.request.trigger is UNPushNotificationTrigger {
            host.receivedNotification(
                id: content.userInfo["gcm.message_id"] as? String ?? response.notification.request.identifier,
                title: content.title, body: content.body, destination: destination
            ) { completionHandler() }
        } else { completionHandler() }
    }

    func enterBackground() {
        host.stopForegroundWork()
        scheduleSync()
    }

    private func scheduleSync() {
        let request = BGAppRefreshTaskRequest(identifier: syncIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do { try BGTaskScheduler.shared.submit(request) }
        catch { NSLog("Background sync scheduling unavailable (%@)", String(describing: type(of: error))) }
    }

    func applicationWillTerminate(_ application: UIApplication) { host.close() }
}
