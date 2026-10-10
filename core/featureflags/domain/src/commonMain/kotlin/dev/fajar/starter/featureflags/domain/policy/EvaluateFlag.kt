package dev.fajar.starter.featureflags.domain.policy

import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.featureflags.domain.entities.*

/** A local override never affects a production evaluation, including restored storage. */
fun evaluateFlag(
    flag: BooleanFlag,
    snapshot: FlagSnapshot,
    environment: AppEnvironment,
): FlagEvaluation {
    if (environment != AppEnvironment.Prod) {
        snapshot.overrides[flag.key]?.let {
            return FlagEvaluation(it, FlagSource.Override)
        }
    }
    val remote = snapshot.remoteValues[flag.key]?.trim()?.lowercase()?.toBooleanStrictOrNull()
    return if (remote != null) FlagEvaluation(remote, FlagSource.Remote)
    else FlagEvaluation(flag.defaultValue, FlagSource.Default)
}
