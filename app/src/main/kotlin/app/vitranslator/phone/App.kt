package app.vitranslator.phone

import android.app.Application
import app.vitranslator.phone.core.Core
import app.vitranslator.phone.core.Cores
import app.vitranslator.phone.read.MlKitRecognizer
import app.vitranslator.phone.read.Reader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Crashes.install(this)
        core = Cores.create(this, MlKitRecognizer())
        // The TV page is served while the app runs (as the PC app does while open): a connected TV
        // finds the phone at once, and shows the translations when translating starts.
        Thread {
            try {
                core.startServer()
            } catch (e: Exception) {
                android.util.Log.w("App", "the TV page couldn't start", e)
            }
        }.start()
    }

    companion object {
        /** The translation core (the real one when this build has it, else the stand-in). */
        lateinit var core: Core
            private set

        /** Reading the game, while translating (TranslateService sets it). */
        @Volatile
        var reader: Reader? = null
    }
}
