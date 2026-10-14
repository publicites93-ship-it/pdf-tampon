package fr.pdftampon.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {

    private val vm: StampViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                StampApp(vm)
            }
        }
    }
}

@Composable
fun StampApp(vm: StampViewModel) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addPdfs(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setImage(uri)
    }
    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setOutput(uri)
    }

    BackHandler(enabled = ui.screen != Screen.HOME && ui.screen != Screen.PROCESSING) { vm.goBack() }

    Surface(Modifier.fillMaxSize()) {
        when (ui.screen) {
            Screen.HOME -> HomeScreen(
                ui = ui,
                vm = vm,
                onPickPdfs = { pdfPicker.launch(arrayOf("application/pdf")) },
                onPickFolder = { folderPicker.launch(null) },
                onPickImage = { imagePicker.launch(arrayOf("image/png", "image/jpeg")) },
                onPickOutput = { outputPicker.launch(null) },
            )
            Screen.POSITION -> PositionScreen(ui = ui, vm = vm)
            Screen.SETTINGS -> SettingsScreen(
                ui = ui,
                vm = vm,
                onPickOutput = { outputPicker.launch(null) },
            )
            Screen.PROCESSING -> ProcessingScreen(ui = ui, vm = vm)
            Screen.RESULTS -> ResultsScreen(ui = ui, vm = vm, context = context)
        }
    }
}
