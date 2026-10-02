package app.parley.ui.menus

/** Who "Call with a reason…" is for: the number (as it would be dialled), a name to show, and a SIM already picked. */
data class ReasonTarget(val number: String, val name: String?, val simId: String? = null)

/** Where the flow is: the sheet, waiting in the messaging app, or back and asking to call. */
internal enum class ReasonStage { SHEET, TEXTING, ASK_CALL }
