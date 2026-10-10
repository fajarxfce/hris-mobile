package dev.fajar.starter.observability

interface ApplePerformanceClient {
    fun start(name: String): ApplePerformanceSample?
}

interface ApplePerformanceSample {
    fun finish(outcome: String)
}

class ApplePerformanceSink(private val client: ApplePerformanceClient) : PerformanceSink {
    override fun start(operation: PerformanceOperation): PerformanceSample? {
        val sample = client.start("starter_" + operation.name) ?: return null
        return PerformanceSample { sample.finish(it.name) }
    }
}
