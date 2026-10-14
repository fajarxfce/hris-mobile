package dev.fajar.starter.people.presentation.profile

import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.profile.entities.*
import dev.fajar.starter.people.domain.profile.policies.selfProfileFailureClosesRead

fun ProfileState.withProfileView(result: AppResult<SelfProfileView?>): ProfileState =
    when (result) {
        is AppResult.Failed -> {
            val restricted = readRestricted || selfProfileFailureClosesRead(result.failure)
            copy(
                view = view?.takeUnless { restricted },
                loading = false,
                readRestricted = restricted,
                choosingEmployment = choosingEmployment && !restricted,
                cacheFailure =
                    cacheFailure?.takeIf(::selfProfileFailureClosesRead) ?: result.failure,
            )
        }
        is AppResult.Success -> {
            val value = result.value
            // Queued local emissions cannot reopen a denied read or replace a newer selection.
            if (readRestricted || (value != null && value.requestedId != selectedId)) this
            else
                copy(
                    view = value,
                    selectedId = value?.selectedId ?: selectedId,
                    loading = value == null && syncing,
                    cacheFailure = null,
                )
        }
    }

fun ProfileState.withProfileSync(result: AppResult<SelfProfileSyncOutcome>): ProfileState =
    when (result) {
        is AppResult.Failed -> {
            val restricted = readRestricted || selfProfileFailureClosesRead(result.failure)
            copy(
                view = view?.takeUnless { restricted },
                loading = false,
                syncing = false,
                readRestricted = restricted,
                choosingEmployment = choosingEmployment && !restricted,
                syncFailure = syncFailure?.takeIf(::selfProfileFailureClosesRead) ?: result.failure,
            )
        }
        is AppResult.Success ->
            copy(
                loading = false,
                syncing = false,
                syncPending = !result.value.caughtUp,
                readRestricted = readRestricted && !result.value.caughtUp,
                syncFailure = if (result.value.caughtUp) null else syncFailure,
                cacheFailure = if (result.value.caughtUp) null else cacheFailure,
            )
    }
