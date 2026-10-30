package dev.fajar.starter.documents.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import kotlinx.datetime.LocalDate

fun prepareDocumentBusinessMetadata(
    input: DocumentMetadataInput
): AppResult<DocumentBusinessMetadata> {
    if (
        input.identityNumber.length > 240 ||
            input.issuingCountry.length > 8 ||
            input.issuedOn.length > 16 ||
            input.validUntil.length > 16
    )
        return documentFailure("client.document_metadata_invalid")
    val issued = input.issuedOn.trim()
    val until = input.validUntil.trim()
    val dates =
        try {
            (issued.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }) to
                (until.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) })
        } catch (_: IllegalArgumentException) {
            return documentFailure("client.document_metadata_invalid")
        }
    if (
        (issued.isNotEmpty() && issued != dates.first.toString()) ||
            (until.isNotEmpty() && until != dates.second.toString())
    )
        return documentFailure("client.document_metadata_invalid")
    return validateDocumentBusinessMetadata(
        DocumentBusinessMetadata(
            input.category,
            input.identityNumber.trim().takeIf { it.isNotEmpty() },
            input.issuingCountry.trim().uppercase().takeIf { it.isNotEmpty() },
            dates.first,
            dates.second,
        )
    )
}

fun validateDocumentBusinessMetadata(
    value: DocumentBusinessMetadata
): AppResult<DocumentBusinessMetadata> {
    val number = value.identityNumber
    if (
        (number != null &&
            (number.isBlank() ||
                number != number.trim() ||
                number.length > 120 ||
                number.any { it.code in 0..31 || it.code in 127..159 })) ||
            (value.issuingCountry != null && value.issuingCountry !in documentIssuingCountries) ||
            (value.issuedOn != null && value.issuedOn.year !in 1900..2200) ||
            (value.validUntil != null && value.validUntil.year !in 1900..2200) ||
            (value.issuedOn != null &&
                value.validUntil != null &&
                value.validUntil < value.issuedOn)
    )
        return documentFailure("client.document_metadata_invalid")
    return AppResult.Success(value)
}

// ISO 3166-1 alpha-2. Keep country validation aligned with Platform's Locale.getISOCountries().
private val documentIssuingCountries =
    ("AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ " +
            "CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR " +
            "GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP " +
            "KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ " +
            "NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ " +
            "TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW")
        .split(' ')
        .toSet()
