import com.android.build.api.artifact.SingleArtifact
import tasks.TransformAppLinksManifest

plugins { id("com.android.application") }

val appLinksDirectory =
    providers.gradleProperty("appLinksConfigDirectory").orElse("config/app-links")

androidComponents.onVariants { variant ->
    val environment = variant.productFlavors.single { it.first == "environment" }.second
    val transform =
        tasks.register<TransformAppLinksManifest>(
            "configure${variant.name.replaceFirstChar { it.uppercase() }}AppLinks"
        ) {
            configurationFiles.from(
                rootProject.fileTree(appLinksDirectory.get()) { include("*.json") }
            )
            baseApplicationId.set(requireNotNull(android.defaultConfig.applicationId))
            applicationId.set(variant.applicationId)
            this.environment.set(environment)
        }
    variant.artifacts
        .use(transform)
        .wiredWithFiles(
            TransformAppLinksManifest::inputManifest,
            TransformAppLinksManifest::outputManifest,
        )
        .toTransform(SingleArtifact.MERGED_MANIFEST)
}
