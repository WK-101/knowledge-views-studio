package app.parley.calls

import android.content.Context
import app.parley.common.calls.MenuMemory
import app.parley.common.calls.MenuPath
import app.parley.common.calls.MenuPress
import app.parley.common.calls.MenuShortcut
import app.parley.common.calls.MenuStep
import app.parley.data.DataContainer
import app.parley.data.EmergencyNumbers
import app.parley.data.PhoneEnv
import app.parley.telecom.MenuMemoryHooks
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * I6 menu memory for the call screen ([MenuMemoryHooks]), on [DataContainer.menus]. Every way in checks the number
 * again: never an emergency number (the platform's list, not only the fallback one), never a service code.
 */
class MenuMemoryBridge(private val app: Context, private val c: DataContainer) : MenuMemoryHooks {
    private fun allowed(number: String): Boolean =
        MenuMemory.remembers(number, runCatching { EmergencyNumbers.isEmergency(app, MenuMemory.dialled(number)) }.getOrDefault(true))

    private fun key(number: String, accountId: String?): String = MenuMemory.key(number, PhoneEnv.countryIso(app, accountId))

    override suspend fun menuPath(number: String, accountId: String?): MenuPath? = withContext(Dispatchers.IO) {
        if (!allowed(number)) return@withContext null
        val state = c.menus.load()
        if (!c.menus.available) return@withContext null
        MenuMemory.pathFor(state, key(number, accountId))
    }

    override fun onMenuKeys(number: String, accountId: String?, presses: List<MenuPress>) {
        c.scope.launch(Dispatchers.IO) {
            runCatching {
                if (!allowed(number)) return@runCatching
                val path = MenuMemory.record(presses, System.currentTimeMillis(), number) ?: return@runCatching
                val k = key(number, accountId)
                c.menus.update { MenuMemory.remember(it, k, path) }
            }
        }
    }

    override suspend fun saveMenuShortcut(number: String, accountId: String?, name: String, steps: List<MenuStep>): Boolean = withContext(Dispatchers.IO) {
        if (!allowed(number)) return@withContext false
        val region = PhoneEnv.countryIso(app, accountId)
        val shortcut = MenuShortcut(UUID.randomUUID().toString(), name, number, steps, System.currentTimeMillis())
        val after = c.menus.update { MenuMemory.addShortcut(it, shortcut, region) } ?: return@withContext false
        after.shortcuts.any { it.steps == steps && it.name == MenuMemory.cleanName(name) }
    }

    override suspend fun stopMenuMemory(number: String, accountId: String?) {
        withContext(Dispatchers.IO) {
            val k = key(number, accountId)
            c.menus.update { MenuMemory.setOptOut(it, k, true) }
        }
    }
}
