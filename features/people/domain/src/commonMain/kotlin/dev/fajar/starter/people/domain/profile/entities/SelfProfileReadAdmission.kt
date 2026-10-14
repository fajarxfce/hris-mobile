package dev.fajar.starter.people.domain.profile.entities

import dev.fajar.starter.common.result.Failure

data class SelfProfileReadAdmission(val revision: String?, val denial: Failure?)
