package app.parley.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.jobs.UserJobs

/**
 * Shows the end of a job ([UserJobs]) as the app's snackbar while a Parley screen is in front. While nothing
 * collects (the app is in the background), the job posts a notification instead.
 */
@Composable
fun JobResultsHost(vm: AppViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.jobs.finished.collect { vm.toast(it.message) }
        }
    }
}

/**
 * A progress bar with its label while a job of one of [kinds] runs: on the screen that started it, also after leaving
 * and coming back. Nothing while none runs.
 */
@Composable
fun JobProgress(vm: AppViewModel, vararg kinds: UserJobs.Kind, modifier: Modifier = Modifier) {
    val running by vm.jobs.running.collectAsStateWithLifecycle()
    val job = running.lastOrNull { it.kind in kinds } ?: return
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        val fraction = job.fraction
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(job.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        Text(stringResource(R.string.job_running), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
