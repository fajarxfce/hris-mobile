package dev.fajar.starter.common.config

data class AppBuild(
    val platform: AppPlatform,
    val number: Long,
    val version: String,
    val updateUrl: String?,
) {
    init {
        require(number > 0 && version.isNotBlank())
    }
}
