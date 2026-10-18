package dev.optrolley.autoroute

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.optrolley.autoroute.ui.OptrolleyApp
import dev.optrolley.autoroute.ui.OptrolleyTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels {
        viewModelFactory {
            initializer { AppViewModel(HistoryStore(File(filesDir, HISTORY_FILE))) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OptrolleyTheme {
                OptrolleyApp(vm)
            }
        }
    }

    companion object {
        const val HISTORY_FILE = "history.jsonl"
    }
}
