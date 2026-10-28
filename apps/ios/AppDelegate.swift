import UIKit
import BackgroundTasks
import UserNotifications
import FirebaseCore
import FirebaseCrashlytics
import FirebasePerformance
import FirebaseMessaging
import StarterKit

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate, AppleFirebaseClient {
    lazy var host = AppleAppHost(firebase: self, remoteConfig: FirebaseFeatureFlagClient(), credentials: KeychainClient(), inboxCredentials: KeychainClient(recordName: "inbox-content-key"), calendarCredentials: KeychainClient(recordName: "calendar-content-key"), leaveCredentials: KeychainClient(recordName: "leave-content-key"), leaveReplicaCredentials: KeychainClient(recordName: "leave-replica-content-key"), overtimeReplicaCredentials: KeychainClient(recordName: "overtime-replica-content-key"), workforceReplicaCredentials: KeychainClient(recordName: "workforce-replica-content-key"), payrollReplicaCredentials: KeychainClient(recordName: "payroll-replica-content-key"), expenseReplicaCredentials: KeychainClient(recordName: "expense-replica-content-key"), directoryReplicaCredentials: KeychainClient(recordName: "directory-replica-content-key"), selfProfileReplicaCredentials: KeychainClient(recordName: "self-profile-replica-content-key"), documentCatalogCredentials: KeychainClient(recordName: "document-catalog-content-key"), commandCredentials: KeychainClient(recordName: "command-content-key"), fileCredentials: KeychainClient(recordName: "file-content-key"), workspaceCredentials: KeychainClient(recordName: "workspace-content-key"), pushCredentials: KeychainClient(recordName: "native-push-state"), inboxPushCredentials: KeychainClient(recordName: "inbox-push-queue"), crash: FirebaseCrashClient(), performance: FirebasePerformanceClient())
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

    func deleteToken(completion: @escaping (Error?) -> Void) {
        guard configured else { completion(NSError(domain: "FirebaseConfiguration", code: 1)); return }
        Messaging.messaging().deleteToken { error in completion(error as NSError?) }
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        if configured { Messaging.messaging().apnsToken = deviceToken }
    }

    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        if let token = fcmToken { host.receivedToken(token: token) }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        if notification.request.trigger is UNPushNotificationTrigger {
            guard let fields = inboxFields(notification.request.content.userInfo) else { completionHandler([]); return }
            host.receivedInboxPush(fields: fields, process: false) { _ in completionHandler([]) }
        } else { completionHandler([.banner, .sound, .list]) }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let content = response.notification.request.content
        if response.notification.request.trigger is UNPushNotificationTrigger {
            guard let fields = inboxFields(content.userInfo) else { completionHandler(); return }
            host.receivedInboxPush(fields: fields, process: false) { _ in completionHandler() }
        } else {
            if let destination = content.userInfo["destination"] as? String { host.openNotification(destination: destination) }
            completionHandler()
        }
    }

    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any], fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        guard let fields = inboxFields(userInfo) else { completionHandler(.noData); return }
        host.receivedInboxPush(fields: fields, process: true) { progressed in
            completionHandler(progressed.boolValue ? .newData : .noData)
        }
    }

    private func inboxFields(_ userInfo: [AnyHashable: Any]) -> [String: String]? {
        var fields: [String: String] = [:]
        for name in ["schemaVersion", "eventCode", "eventId", "accountId", "companyId", "inboxId"] {
            guard let value = userInfo[name] as? String, value.count <= 256 else { return nil }
            fields[name] = value
        }
        return fields
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
