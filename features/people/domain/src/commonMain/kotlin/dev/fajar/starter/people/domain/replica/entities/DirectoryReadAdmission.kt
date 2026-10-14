package dev.fajar.starter.people.domain.replica.entities

import dev.fajar.starter.common.result.Failure

data class DirectoryReadAdmission(val revision: String?, val denial: Failure?)
