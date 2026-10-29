package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class TransformAppLinksManifest : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configurationFiles: ConfigurableFileCollection
    @get:Input abstract val baseApplicationId: Property<String>
    @get:Input abstract val applicationId: Property<String>
    @get:Input abstract val environment: Property<String>
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputManifest: RegularFileProperty
    @get:OutputFile abstract val outputManifest: RegularFileProperty

    @TaskAction
    fun transform() {
        val registration =
            readAppLinkRegistrations(configurationFiles.files, baseApplicationId.get())
                .singleOrNull { it.environment == environment.get() }
        require(registration == null || registration.applicationId == applicationId.get()) {
            "App-link registration does not match the assembled Android variant."
        }
        val source = inputManifest.get().asFile.readText()
        val result = appLinkManifest(source, registration)
        outputManifest.get().asFile.apply {
            parentFile.mkdirs()
            writeText(result)
        }
    }
}
