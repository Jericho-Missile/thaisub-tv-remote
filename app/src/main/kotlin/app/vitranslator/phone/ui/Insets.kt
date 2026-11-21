package app.vitranslator.phone.ui

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Keeps a screen's content clear of the status bar, the navigation bar, a camera cutout and the
 * keyboard. From Android 15 an app targeting it is drawn edge to edge, under the bars (the bars'
 * colours are no longer applied): without this the app's title sits under the status bar and its
 * last rows under the navigation bar. The view's background still fills the space behind the bars.
 */
fun View.clearOfSystemBars() {
    setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        insets
    }
    requestApplyInsets()
}
