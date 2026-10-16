package dev.fajar.starter.home.presentation.datasync

data class DataSyncState(val companyName: String, val rows: List<DataSyncRowState>) {
    val syncing: Boolean
        get() = rows.any { it.syncing }

    val needsVerification: Boolean
        get() =
            rows.any {
                it.failure?.code in
                    setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
            }
}
