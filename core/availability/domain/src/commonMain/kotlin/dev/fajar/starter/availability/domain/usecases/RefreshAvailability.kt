package dev.fajar.starter.availability.domain.usecases

import dev.fajar.starter.featureflags.domain.repositories.FeatureFlagRepository
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit recovery fetch; periodic refresh retains its existing interval policy. */
class RefreshAvailability(
    private val flags: FeatureFlagRepository,
    private val clock: Clock = Clock.System,
) {
    private val refresh = Mutex()

    suspend operator fun invoke() =
        refresh.withLock { flags.refresh(clock.now().toEpochMilliseconds()) }
}
