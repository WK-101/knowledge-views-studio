package app.parley

import android.app.Application
import app.parley.data.DataContainer
import app.parley.telecom.TelecomGraph

class ParleyApp : Application() {
    lateinit var container: DataContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DataContainer(this)
        TelecomGraph.install(AppTelecomDependencies(this, container))
    }
}

val android.content.Context.container: DataContainer get() = (applicationContext as ParleyApp).container
