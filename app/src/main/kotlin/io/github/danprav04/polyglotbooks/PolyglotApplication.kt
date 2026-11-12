package io.github.danprav04.polyglotbooks

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Main application class for Polyglot Books.
 * Annotated with [@HiltAndroidApp] to trigger Hilt component generation and dependency injection.
 */
@HiltAndroidApp
class PolyglotApplication : Application() {

    override fun onCreate() {
        super.onCreate()
    }
}
