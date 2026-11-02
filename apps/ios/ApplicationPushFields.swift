import Foundation

/// Remove provider metadata only. Shared domain parsers validate each application envelope.
func applicationPushFields(_ userInfo: [AnyHashable: Any]) -> [String: String]? {
    guard userInfo.count <= 32 else { return nil }
    let names: Set<String>
    switch userInfo["eventCode"] as? String {
    case "communications.inbox_available":
        names = ["schemaVersion", "eventCode", "eventId", "accountId", "companyId", "inboxId"]
    case "collaboration.chat_available":
        names = ["schemaVersion", "eventCode", "eventId", "accountId", "companyId", "nativeSessionId", "deviceId", "revision", "issuedAt", "expiresAt", "matrixRoomId", "matrixEventId"]
    default:
        return nil
    }
    var fields: [String: String] = [:]
    for (rawName, rawValue) in userInfo {
        guard let name = rawName as? String, name.utf16.count <= 64 else { return nil }
        if name == "aps" || name.hasPrefix("gcm.") || name.hasPrefix("google.c.") { continue }
        guard names.contains(name), let value = rawValue as? String, value.utf16.count <= 256 else { return nil }
        fields[name] = value
    }
    return fields
}
