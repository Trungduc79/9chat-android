package vn.chat9.app

import android.app.Application
import vn.chat9.app.call.CallManager
import vn.chat9.app.di.AppContainer

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Bootstrap V2 CallManager BEFORE any Service or Activity runs.
        // Android creates the App instance before FCMService is dispatched,
        // so this guarantees the lateinit `audio` / `socket` / `appContext`
        // are set by the time FCM delivers a call_event=incoming payload.
        // Previously init() only ran inside MainActivity.setContent, so FCM
        // arrivals to a killed/locked app crashed with
        //   UninitializedPropertyAccessException: lateinit property audio
        //   has not been initialized
        // (stack trace at CallManager.kt:334 — handleIncomingInternal).
        CallManager.init(this, container.socket)
    }
}
