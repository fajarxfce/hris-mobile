package dev.fajar.starter.inbox.domain.entities

import dev.fajar.starter.common.result.Failure

/** Confirmed read restrictions are independent of retained facts and their age. */
data class InboxReadAdmission(val revision: String?, val denial: Failure?)
