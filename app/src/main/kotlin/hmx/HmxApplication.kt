package hmx

import android.app.Application
import hmx.core.logging.HmxLog
import hmx.di.AppContainer

class HmxApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        HmxLog.verbose = false
        container = AppContainer(this)
        container.initEngine(this)
        HmxLog.i("App") { "HMX started" }
    }
}
