package tasks

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class GenerateFirebaseWebResources : DefaultTask() {
    @get:Input abstract val sdkVersion: Property<String>
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val configuration: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = outputDirectory.get().asFile.apply { mkdirs() }
        val config =
            configuration.orNull?.asFile?.let { JsonSlurper().parse(it) } ?: emptyMap<String, Any>()
        directory
            .resolve("firebase-config.js")
            .writeText("self.FLUENT_FIREBASE = ${JsonOutput.toJson(config)};\n")
        directory
            .resolve("firebase-version.js")
            .writeText("self.FIREBASE_WEB_SDK_VERSION = ${JsonOutput.toJson(sdkVersion.get())};\n")
    }
}
