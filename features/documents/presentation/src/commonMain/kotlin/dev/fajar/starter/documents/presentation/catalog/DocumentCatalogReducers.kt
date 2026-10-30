package dev.fajar.starter.documents.presentation.catalog

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.replica.entities.DocumentCatalogSnapshot
import dev.fajar.starter.documents.domain.replica.policies.documentCatalogFailureClosesRead

fun DocumentCatalogState.withCatalog(
    result: AppResult<DocumentCatalogSnapshot>
): DocumentCatalogState =
    when (result) {
        is AppResult.Failed -> withCatalogFailure(result.failure)
        is AppResult.Success -> {
            val snapshot = result.value
            if (snapshot.selection != selection) this
            else {
                val directory = snapshot.checkpoint?.employments
                val chosen =
                    directory?.firstOrNull { it.id == selection.employmentId }
                        ?: directory?.singleOrNull()
                val changed = chosen?.id != selection.employmentId
                copy(
                    employments = directory,
                    selection =
                        if (changed) selection.copy(employmentId = chosen?.id, after = null)
                        else selection,
                    catalog = snapshot.takeIf { it.checkpoint != null && !changed },
                    choosingEmployment =
                        choosingEmployment ||
                            (employments == null && !directory.isNullOrEmpty() && chosen == null),
                    loading = changed,
                    preparingId =
                        if (changed) null
                        else
                            preparingId?.takeIf { id ->
                                snapshot.page.items.any { it.evidence.revisionId == id }
                            },
                    restriction = null,
                )
            }
        }
    }

fun DocumentCatalogState.withCatalogFailure(error: Failure): DocumentCatalogState =
    if (documentCatalogFailureClosesRead(error))
        copy(
            employments = null,
            catalog = null,
            loading = false,
            choosingEmployment = false,
            preparingId = null,
            restriction = error,
        )
    else copy(loading = false, failure = error)
