package dev.fajar.hris.collaboration.proof

import android.app.Activity
import android.os.Bundle

/** Staging uses a normal process so instrumentation death cannot force-stop the package. */
class HrisScheduledProofActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireNotNull((application as HrisScheduledProofApplication).proof).startStage()
    }
}
