package dev.fajar.starter.storage

import kotlinx.browser.localStorage

class BrowserPreferenceStore : PreferenceStore {
    override suspend fun readBoolean(key: String): Boolean? =
        localStorage.getItem("fluent-starter.$key")?.toBooleanStrict()

    override suspend fun writeBoolean(key: String, value: Boolean): Boolean {
        localStorage.setItem("fluent-starter.$key", value.toString())
        return true
    }
}
