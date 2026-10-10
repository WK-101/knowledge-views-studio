package app.parley.calls

import android.content.Context
import app.parley.common.calls.CallQualityFacts
import app.parley.common.calls.MenuMemory
import app.parley.common.calls.MenuPress
import app.parley.common.cases.CaseCall
import app.parley.common.cases.CaseFiles
import app.parley.common.catching
import app.parley.data.DataContainer
import app.parley.data.EmergencyNumbers
import app.parley.data.PhoneEnv
import app.parley.telecom.CaseFileHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Case files for the call path ([CaseFileHooks]), on [DataContainer.cases]. A call is kept only for a number with a case
 * file, or a saved organisation's (which starts one); never an emergency number. With "Private call history" on, a call
 * with a private contact leaves no trace outside the vault, here either. Menu keys are kept as menu memory keeps them:
 * only while "Remember menu keys" is on, never for a number it was told not to remember, never anything secret-looking.
 */
class CaseFileBridge(private val app: Context, private val c: DataContainer) : CaseFileHooks {
    private fun emergency(number: String): Boolean = catching { EmergencyNumbers.isEmergency(app, number) }.getOrDefault(true)

    override fun onCaseCall(number: String, accountId: String?, facts: CallQualityFacts, keys: List<MenuPress>) {
        c.scope.launch(Dispatchers.IO) {
            catching {
                if (emergency(number)) return@catching
                val iso = PhoneEnv.countryIso(app, accountId)
                val vaultNumber = c.vault.lookup(number) != null
                if (vaultNumber && c.settings.current().privateVaultHistory) return@catching
                val state = c.cases.load()
                if (!c.cases.available) return@catching
                val found = CaseFiles.find(state, listOf(number), iso)
                // Who the number is saved for is looked up only when no case file has it yet.
                val org = if (found == null) NeverCallsYouFacts.organisation(c, number, iso) ?: return@catching else null
                val call = CaseCall(facts.startedAt, facts.incoming, facts.durationSec, facts.holdSec, facts.connected, menuOf(number, accountId, keys))
                c.cases.update {
                    CaseFiles.recordCall(it, number, call, org != null, org?.name.orEmpty(), org?.private ?: vaultNumber, iso, UUID.randomUUID().toString())
                }
            }
        }
    }

    /** The keys to keep with the call: what menu memory would keep, nothing when it is off for the number. */
    private suspend fun menuOf(number: String, accountId: String?, keys: List<MenuPress>): String {
        if (keys.isEmpty()) return ""
        val on = catching { c.callExtras.config.value.rememberMenuKeys }.getOrDefault(false)
        if (!on || !MenuMemory.remembers(number, emergency = false)) return ""
        val state = c.menus.load()
        val key = MenuMemory.key(number, PhoneEnv.countryIso(app, accountId))
        if (!c.menus.available || key in state.optOut) return ""
        return CaseFiles.menuOf(keys, remember = true)
    }

    /** Never while Parley's app lock is locked or a duress unlock hides notes: the keypad shows over the lock screen. */
    private fun mayShow(): Boolean = c.privacy.memory().let { !it.appLocked && it.notesShown }

    override suspend fun hasCaseFile(number: String, accountId: String?): Boolean = withContext(Dispatchers.IO) {
        if (!mayShow() || emergency(number)) return@withContext false
        val state = c.cases.load()
        val case = CaseFiles.find(state, listOf(number), PhoneEnv.countryIso(app, accountId))
        // Whether the contact is private is asked now, not taken from when the case was made.
        case != null && case.kept && (c.privacy.now().privateShown || !c.cases.isPrivateNow(case))
    }

    override suspend fun keepCaseReference(number: String, accountId: String?, reference: String): Boolean = withContext(Dispatchers.IO) {
        if (!hasCaseFile(number, accountId)) return@withContext false
        val case = CaseFiles.find(c.cases.load(), listOf(number), PhoneEnv.countryIso(app, accountId)) ?: return@withContext false
        c.cases.addReference(case.id, label = "", value = reference, typed = true)
    }
}
