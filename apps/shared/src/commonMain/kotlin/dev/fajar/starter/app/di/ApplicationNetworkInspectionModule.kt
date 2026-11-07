package dev.fajar.starter.app.di

import dev.fajar.inspector.NetworkInspection
import dev.fajar.starter.app.inspection.ApplicationNetworkInspection
import org.koin.dsl.module
import org.koin.dsl.onClose

/** The container starts and disposes the account-scoped capture owner. */
fun applicationNetworkInspectionModule(inspection: NetworkInspection?) = module {
    if (inspection != null) {
        single(createdAtStart = true) { ApplicationNetworkInspection(inspection, get()) }
            .onClose { it?.close() }
    }
}
