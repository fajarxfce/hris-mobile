package dev.fajar.fluent

import android.app.Application
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.storage.AndroidPreferenceStore

class StarterApplication : Application() {
    val container by lazy { createAppContainer(AndroidPreferenceStore(this)) }
}
