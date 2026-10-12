package app.parley.ui.calls

import android.app.Application
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.calls.RemindTime
import app.parley.common.calls.RoundMark
import app.parley.common.calls.RoundPerson
import app.parley.common.calls.ToCall
import java.time.ZoneId

/**
 * "No answer" in a call round: [person] is marked (they come round again once everyone was tried), and a snackbar
 * offers **Add to To call**. Nothing goes on the list by itself.
 */
internal fun markNoAnswer(vm: AppViewModel, person: RoundPerson) {
    vm.callRound.mark(person.key, RoundMark.NO_ANSWER)
    val res = vm.getApplication<Application>().resources
    vm.offer(res.getString(R.string.round_no_answer_from, person.name), res.getString(R.string.round_add_to_call)) { addToCall(vm, person.number) }
}

/**
 * Puts a round's number on To call in an hour, as "Remind me" does (by number: a private contact's item holds no
 * name), with Undo.
 */
internal fun addToCall(vm: AppViewModel, number: String) {
    val now = System.currentTimeMillis()
    remindWithUndo(vm, number, null, ToCall.at(RemindTime.IN_AN_HOUR, now, ZoneId.systemDefault()))
}
