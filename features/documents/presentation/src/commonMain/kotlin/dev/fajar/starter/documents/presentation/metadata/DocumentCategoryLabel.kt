package dev.fajar.starter.documents.presentation.metadata

import dev.fajar.starter.documents.domain.entities.DocumentCategory
import dev.fajar.starter.localization.AppString

fun DocumentCategory.label(): AppString =
    when (this) {
        DocumentCategory.PASSPORT -> AppString.DocumentPassport
        DocumentCategory.NATIONAL_ID -> AppString.DocumentNationalId
        DocumentCategory.WORK_PERMIT -> AppString.DocumentWorkPermit
        DocumentCategory.RESIDENCE_PERMIT -> AppString.DocumentResidencePermit
        DocumentCategory.EMPLOYMENT_CONTRACT -> AppString.DocumentEmploymentContract
        DocumentCategory.CERTIFICATE -> AppString.DocumentCertificate
        DocumentCategory.RECEIPT -> AppString.DocumentCategoryReceipt
        DocumentCategory.OTHER -> AppString.DocumentCategoryOther
    }
