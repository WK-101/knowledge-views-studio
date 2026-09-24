package app.parley.lists

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.parley.ui.ParleyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = ListsRepo.get(this)
        UpdateWorker.schedule(this, repo.state.value.config)
        // First launch: fetch soon (the first FTC window is ~20-30 MB, so it still waits for Wi-Fi if asked to).
        if (savedInstanceState == null && repo.state.value.lastRun == 0L) UpdateWorker.runNow(this, repo.state.value.config.unmeteredOnly)
        setContent { ParleyTheme { UpdaterScreen(repo) } }
    }
}
