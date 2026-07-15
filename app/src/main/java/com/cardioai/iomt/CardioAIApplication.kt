package com.cardioai.iomt

import android.app.Application
import com.cardioai.iomt.core.AppContainer

class CardioAIApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.getInstance(this)
    }
}
