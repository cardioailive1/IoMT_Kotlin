package com.cardioai.iomt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cardioai.iomt.core.AppContainer
import com.cardioai.iomt.ui.CardioAINavHost
import com.cardioai.iomt.ui.theme.CardioAITheme

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = (application as CardioAIApplication).container

        setContent {
            CardioAITheme {
                CardioAINavHost(container = container)
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Routes the Google Health OAuth redirect (cardioai://google-health-callback)
     * back to GoogleHealthService, which is waiting on it via a
     * CompletableDeferred — see GoogleHealthService.connect()/handleRedirect().
     */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = intent?.data
        if (uri != null && uri.scheme == "cardioai" && uri.host == "google-health-callback") {
            container.googleHealthService.handleRedirect(uri)
        }
    }
}
