package dev.fajar.hris.collaboration.proof

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.channels.Channel

/** Separately packaged emulator fixture. Inputs stay in memory and contain fictional text only. */
class NotificationProofReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        received.trySend(Intent(intent))
    }

    companion object {
        internal val received = Channel<Intent>(8)
    }
}
