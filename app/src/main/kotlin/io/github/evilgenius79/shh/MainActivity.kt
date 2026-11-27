package io.github.evilgenius79.shh

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.evilgenius79.shh.ui.ShhRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as ShhApp).container
        // Not after a rotation: the intent is still the old one then, and it has been dealt with.
        if (savedInstanceState == null) take(intent)
        setContent { ShhRoot(container) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
    }

    /** Hands a shortcut, a notification or a link that opened the app on to the UI. */
    private fun take(intent: Intent?) {
        Launch.from(intent)?.let { (application as ShhApp).container.launch.value = it }
    }
}
