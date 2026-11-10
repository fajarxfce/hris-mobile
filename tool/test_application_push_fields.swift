import Foundation

@main
enum ApplicationPushFieldsChecks {
    static func main() {
        let inbox: [String: String] = [
            "schemaVersion": "1", "eventCode": "communications.inbox_available",
            "eventId": "event", "accountId": "account", "companyId": "company", "inboxId": "inbox"
        ]
        var remote: [AnyHashable: Any] = inbox
        remote["aps"] = ["alert": "Untrusted provider content"]
        remote["gcm.message_id"] = "transport"
        remote["google.c.a.e"] = "1"
        precondition(applicationPushFields(remote) == inbox)

        var chat = inbox.filter { $0.key != "inboxId" }
        chat["eventCode"] = "collaboration.chat_available"
        for key in ["nativeSessionId", "deviceId", "revision", "issuedAt", "expiresAt"] { chat[key] = "value" }
        precondition(applicationPushFields(chat) == chat)
        chat["matrixRoomId"] = "!room:example.test"
        chat["matrixEventId"] = "$event"
        precondition(applicationPushFields(chat) == chat)

        var invalid: [AnyHashable: Any] = chat
        invalid["deviceId"] = 123
        precondition(applicationPushFields(invalid) == nil)
        invalid = chat
        invalid["destination"] = "inbox/arbitrary"
        precondition(applicationPushFields(invalid) == nil)
        invalid = chat
        invalid["eventCode"] = "unrecognized"
        precondition(applicationPushFields(invalid) == nil)
        invalid = chat
        invalid["matrixRoomId"] = String(repeating: "x", count: 257)
        precondition(applicationPushFields(invalid) == nil)
        invalid = chat
        invalid[123] = "non-string key"
        precondition(applicationPushFields(invalid) == nil)
        invalid = chat
        for index in 0..<33 { invalid["google.c.\(index)"] = "transport" }
        precondition(applicationPushFields(invalid) == nil)
        invalid = chat
        invalid["gcm." + String(repeating: "x", count: 65)] = "transport"
        precondition(applicationPushFields(invalid) == nil)
        var group = chat.filter { $0.key != "companyId" && $0.key != "matrixRoomId" && $0.key != "matrixEventId" }
        group["eventCode"] = "collaboration.space_chat_available"
        group["spaceId"] = "space"
        precondition(applicationPushFields(group) == group)
        group["matrixRoomId"] = "!room:example.test"
        group["matrixEventId"] = "$event"
        precondition(applicationPushFields(group) == group)
        var remoteGroup: [AnyHashable: Any] = group
        remoteGroup["aps"] = ["alert": "Untrusted provider content"]
        remoteGroup["gcm.message_id"] = "transport"
        precondition(applicationPushFields(remoteGroup) == group)
        remoteGroup["companyId"] = "company"
        precondition(applicationPushFields(remoteGroup) == nil)
        invalid = chat
        invalid["spaceId"] = "space"
        precondition(applicationPushFields(invalid) == nil)
        invalid = group
        invalid["revision"] = 1
        precondition(applicationPushFields(invalid) == nil)
        print("Application push fields: 16 cases passed")
    }
}
