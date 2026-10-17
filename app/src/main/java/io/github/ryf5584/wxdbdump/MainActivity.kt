package io.github.ryf5584.wxdbdump

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.ryf5584.wxdbdump.platform.Locales
import io.github.ryf5584.wxdbdump.ui.AppRoot

class MainActivity : ComponentActivity() {

    /** Wraps the Context in the chosen locale; recreating the activity applies a change. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Locales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as DbExportApp).container
        setContent {
            AppRoot(container)
        }
    }
}
