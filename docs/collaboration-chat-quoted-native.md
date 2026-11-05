# Native encrypted quoted replies

The third existing native send now quotes the first event. The recipient must
actually decrypt its body and logical ID and read the exact reply relation before
the sender writes its acknowledged checkpoint. The sender is then killed before
its application receipt journal commits. The next process recovers the original
transaction and reply intent. The following cold process reads all three messages
and the quoted reference from the original encrypted history with refused gateway
connections before and after reading. Exactly three encrypted sends remain.

The backend also checks the opaque reply envelope on the encrypted event. Existing
original-device/key, renewal, rejected-auth recovery, receipts, exclusive queue,
process-death, local history and cleanup assertions stay in both native suites.
Driver tests reject missing or non-boolean proof flags and malformed checkpoints.
Private fixture bodies and event/transaction identities stay out of evidence.

Both actual Android/Synapse/backend suites pass: two cases, zero skips/failures,
159.069 seconds, 12,277 unchanged source inputs and unchanged proof APK. Driver31
pass. Root/proof assembly and Android/browser checks pass separately with 2,155
fresh cases. The owned emulator is stopped. This is fixture/native/store proof;
release32 Bima delivery remains under investigation and no new phone installation
or live rollout is claimed here. All54 communications groups remain open.

[Evidence](measurements/chat-quoted-native-2026-11-05.json).
