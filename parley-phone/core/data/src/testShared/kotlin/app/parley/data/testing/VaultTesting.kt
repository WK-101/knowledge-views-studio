package app.parley.data.testing

import app.parley.data.db.VaultDao
import app.parley.data.vault.VaultCrypto
import app.parley.data.vault.VaultRepository
import org.json.JSONObject

/** Test helpers for private contacts stored as older versions of Parley left them. */
object VaultTesting {
    /** The vault's own table (the app's tests don't see Room's database class, so it is read from the repository). */
    private fun dao(vault: VaultRepository): VaultDao =
        VaultRepository::class.java.getDeclaredField("dao").apply { isAccessible = true }.get(vault) as VaultDao

    /** Entry [vaultId] as saved before its caller-ID copy kept the star, labels, ringtone and "send to voicemail". */
    suspend fun asBeforeCallerChoices(vault: VaultRepository, vaultId: Long) {
        val dao = dao(vault)
        val o = JSONObject(String(VaultCrypto.openCallerId(dao.get(vaultId)!!.callerIdBlob)))
        listOf("star", "lb", "rt", "vm", "cs").forEach { o.remove(it) }
        dao.setCallerIdBlob(vaultId, VaultCrypto.sealCallerId(o.toString().toByteArray()))
    }
}
