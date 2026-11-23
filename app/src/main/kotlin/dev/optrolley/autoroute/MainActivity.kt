package dev.optrolley.autoroute

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.optrolley.autoroute.catalogue.OpenFoodFacts
import dev.optrolley.autoroute.scan.CameraScanner
import dev.optrolley.autoroute.ui.OptrolleyApp
import dev.optrolley.autoroute.ui.OptrolleyTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels {
        viewModelFactory {
            initializer {
                AppViewModel(
                    HistoryStore(File(filesDir, HISTORY_FILE)),
                    catalogue = OpenFoodFacts(OpenFoodFacts.agent(versionName(), getString(R.string.catalogue_contact))),
                    // The cache directory, so nothing fetched is ever taken for the user's own data (O47).
                    catalogueCache = File(cacheDir, CATALOGUE_DIR),
                    // Not the cache: unlike what was fetched, these are not to come back off by themselves.
                    options = File(filesDir, OPTIONS_DIR),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val scanner = CameraScanner.takeIf { packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OptrolleyTheme {
                OptrolleyApp(vm, scanner)
            }
        }
    }

    // The flags overload taking PackageInfoFlags is API 33; this one still works on every version.
    @Suppress("DEPRECATION")
    private fun versionName(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"

    companion object {
        const val HISTORY_FILE = "history.jsonl"
        const val CATALOGUE_DIR = "catalogue"
        const val OPTIONS_DIR = "options"
    }
}
