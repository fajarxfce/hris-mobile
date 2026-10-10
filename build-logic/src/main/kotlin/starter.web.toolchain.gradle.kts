import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin

// Use the developer/CI Node installation; distribution downloads do not become
// implicit project repositories under the centralized repository policy.
plugins.withType<WasmNodeJsPlugin> {
    extensions.configure<WasmNodeJsEnvSpec> { download.set(false) }
}

// Resolve the optimizer from the artifact-only repository declared in settings.
@OptIn(ExperimentalWasmDsl::class)
plugins.withType<BinaryenPlugin> {
    extensions.configure<BinaryenEnvSpec> { downloadBaseUrl.set(null as String?) }
}
