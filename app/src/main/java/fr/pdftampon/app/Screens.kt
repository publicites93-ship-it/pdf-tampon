package fr.pdftampon.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

private val Accent = Color(0xFF1565C0)
private val ErrorColor = Color(0xFFB00020)
private val OkColor = Color(0xFF2E7D32)

private fun fmt(v: Float): String = String.format(Locale.FRANCE, "%.1f", v)

private fun fmtSize(bytes: Long): String =
    if (bytes < 0) "taille inconnue" else String.format(Locale.FRANCE, "%.1f Ko", bytes / 1024.0)

@Composable
private fun NumField(label: String, value: Float, modifier: Modifier = Modifier, onValue: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(fmt(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.replace(',', '.').toFloatOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun MessageBar(ui: UiState, vm: StampViewModel) {
    val msg = ui.message ?: return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(msg, color = ErrorColor, modifier = Modifier.weight(1f))
        TextButton(onClick = vm::clearMessage) { Text("OK") }
    }
}

// ---------- Écran 1 : Accueil ----------

@Composable
fun HomeScreen(
    ui: UiState,
    vm: StampViewModel,
    onPickPdfs: () -> Unit,
    onPickFolder: () -> Unit,
    onPickImage: () -> Unit,
    onPickOutput: () -> Unit,
) {
    val valid = ui.pdfs.count { it.valid }
    val invalid = ui.pdfs.size - valid

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("PDF Tampon", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Traitement 100 % hors ligne. Les fichiers originaux ne sont jamais modifiés.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            MessageBar(ui, vm)
            Button(onClick = onPickPdfs, modifier = Modifier.fillMaxWidth()) { Text("Sélectionner les PDF") }
            OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) { Text("Sélectionner un dossier") }
            OutlinedButton(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                Text(ui.stampName?.let { "Image : $it" } ?: "Choisir une image (PNG, JPG)")
            }
            OutlinedButton(onClick = onPickOutput, modifier = Modifier.fillMaxWidth()) {
                Text(ui.outputLabel?.let { "Dossier de sortie : $it" } ?: "Choisir le dossier de sortie")
            }
            Spacer(Modifier.height(8.dp))
            Text("Fichiers sélectionnés : ${ui.pdfs.size} (valides : $valid, invalides : $invalid)")
            if (ui.pdfs.isNotEmpty()) {
                TextButton(onClick = vm::clearPdfs) { Text("Vider la sélection") }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        items(ui.pdfs, key = { it.uri.toString() }) { pdf ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(pdf.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (pdf.valid) fmtSize(pdf.sizeBytes) else "Fichier invalide ou non PDF : sera ignoré",
                    color = if (pdf.valid) Color.Unspecified else ErrorColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            val canPosition = valid > 0 && ui.stamp != null
            Button(
                onClick = vm::openPositioning,
                enabled = canPosition,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Positionner l'image") }
            OutlinedButton(
                onClick = { vm.goTo(Screen.SETTINGS) },
                enabled = ui.page != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Paramètres et traitement") }
        }
    }
}

// ---------- Écran 2 : Positionnement ----------

@Composable
fun PositionScreen(ui: UiState, vm: StampViewModel) {
    val page = ui.page ?: return
    val p = ui.settings.position
    val density = LocalDensity.current
    var previewWidthPx by remember { mutableIntStateOf(0) }
    val pxPerMm = if (previewWidthPx > 0) previewWidthPx / page.widthMm else 1f

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Positionnement", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Faites glisser l'image. Poignée bleue en bas à droite pour redimensionner. " +
                "Position mesurée depuis le coin supérieur gauche de la page.",
            style = MaterialTheme.typography.bodySmall,
        )
        MessageBar(ui, vm)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(page.widthMm / page.heightMm)
                .onSizeChanged { previewWidthPx = it.width }
                .background(Color.White),
        ) {
            ui.preview?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Aperçu de la première page",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            val wDp = with(density) { (p.widthMm * pxPerMm).toDp() }
            val hDp = with(density) { (p.heightMm * pxPerMm).toDp() }
            Box(
                modifier = Modifier
                    .offset { IntOffset((p.xMm * pxPerMm).roundToInt(), (p.yMm * pxPerMm).roundToInt()) }
                    .size(wDp, hDp)
                    .border(2.dp, Accent)
                    .background(Color(0x221565C0))
                    .pointerInput(pxPerMm) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            vm.moveBy(drag.x / pxPerMm, drag.y / pxPerMm)
                        }
                    },
            ) {
                ui.stamp?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Image à ajouter",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(28.dp)
                        .background(Accent)
                        .pointerInput(pxPerMm) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                vm.resizeBy(drag.x / pxPerMm, drag.y / pxPerMm)
                            }
                        },
                )
            }
        }

        Text(
            "Page : ${fmt(page.widthMm)} × ${fmt(page.heightMm)} mm (affichage, rotation incluse)",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("X (mm)", p.xMm, Modifier.weight(1f), vm::setX)
            NumField("Y (mm)", p.yMm, Modifier.weight(1f), vm::setY)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Largeur (mm)", p.widthMm, Modifier.weight(1f), vm::setWidth)
            NumField("Hauteur (mm)", p.heightMm, Modifier.weight(1f), vm::setHeight)
        }

        Button(onClick = { vm.goTo(Screen.SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
            Text("Appliquer cette position")
        }
    }
}

// ---------- Écran 3 : Paramètres ----------

@Composable
fun SettingsScreen(ui: UiState, vm: StampViewModel, onPickOutput: () -> Unit) {
    val s = ui.settings
    val valid = ui.pdfs.count { it.valid }
    val canRun = valid > 0 && ui.stamp != null && ui.outputTree != null

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Paramètres", style = MaterialTheme.typography.headlineSmall)
        MessageBar(ui, vm)

        Text("Opacité : ${(s.opacity * 100).roundToInt()} %")
        Slider(value = s.opacity, onValueChange = vm::setOpacity, valueRange = 0.05f..1f)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Conserver les proportions", modifier = Modifier.weight(1f))
            Switch(checked = s.keepRatio, onCheckedChange = vm::setKeepRatio)
        }

        OutlinedTextField(
            value = s.suffix,
            onValueChange = vm::setSuffix,
            label = { Text("Suffixe ajouté au nom") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Nommage : nom_original + suffixe.pdf. En cas de doublon : (2), (3)… Aucun fichier existant n'est écrasé.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedButton(onClick = onPickOutput, modifier = Modifier.fillMaxWidth()) {
            Text(ui.outputLabel?.let { "Dossier de destination : $it" } ?: "Choisir le dossier de destination")
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = vm::startBatch,
            enabled = canRun,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Lancer le traitement ($valid PDF)") }

        if (!canRun) {
            Text(
                "Il faut au moins un PDF valide, une image et un dossier de destination.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---------- Écran 4 : Traitement ----------

@Composable
fun ProcessingScreen(ui: UiState, vm: StampViewModel) {
    val ok = ui.results.count { it.status == FileStatus.OK }
    val failed = ui.results.size - ok

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Traitement en cours", style = MaterialTheme.typography.headlineSmall)
        LinearProgressIndicator(
            progress = { if (ui.total == 0) 0f else ui.progress.toFloat() / ui.total },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Fichiers traités : ${ui.progress} / ${ui.total}")
        Text("Fichiers restants : ${(ui.total - ui.progress).coerceAtLeast(0)}")
        Text("En cours : ${ui.current}", style = MaterialTheme.typography.bodySmall)
        Text("Réussis : $ok    Échecs : $failed")
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = vm::cancel,
            enabled = ui.running,
            colors = ButtonDefaults.buttonColors(containerColor = ErrorColor),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Annuler") }
    }
}

// ---------- Écran 5 : Résultats ----------

@Composable
fun ResultsScreen(ui: UiState, vm: StampViewModel, context: Context) {
    val ok = ui.results.count { it.status == FileStatus.OK }
    val errors = ui.results.filter { it.status != FileStatus.OK }
    val outputs = ui.results.mapNotNull { it.outUri }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text("Résultats", style = MaterialTheme.typography.headlineSmall)
            MessageBar(ui, vm)
            Text("PDF générés : $ok", color = OkColor, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Échecs ou fichiers refusés : ${errors.size}",
                color = if (errors.isEmpty()) Color.Unspecified else ErrorColor,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { openFolder(context, ui.outputTree) },
                enabled = ui.outputTree != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Ouvrir le dossier") }
            OutlinedButton(
                onClick = { shareFiles(context, outputs) },
                enabled = outputs.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Partager") }
            OutlinedButton(
                onClick = vm::createZip,
                enabled = outputs.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Créer un ZIP") }
            Spacer(Modifier.height(8.dp))
            if (errors.isNotEmpty()) {
                Text("Liste des erreurs", style = MaterialTheme.typography.titleMedium)
            }
        }

        items(errors) { r ->
            Text("${r.name} : ${r.message}", color = ErrorColor, style = MaterialTheme.typography.bodySmall)
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("PDF générés", style = MaterialTheme.typography.titleMedium)
        }

        items(ui.results.filter { it.status == FileStatus.OK }, key = { it.outUri.toString() }) { r ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(r.outName ?: r.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { openPdf(context, r.outUri) }) { Text("Ouvrir") }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { vm.goTo(Screen.HOME) }, modifier = Modifier.fillMaxWidth()) {
                Text("Retour à l'accueil")
            }
        }
    }
}

// ---------- Intents ----------

private fun openFolder(context: Context, tree: Uri?) {
    if (tree == null) return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(tree, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Aucun gestionnaire de fichiers disponible : le dossier reste accessible via l'application Fichiers.
    }
}

private fun openPdf(context: Context, uri: Uri?) {
    if (uri == null) return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Aucun lecteur PDF installé.
    }
}

private fun shareFiles(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "application/pdf"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        clipData = ClipData.newUri(context.contentResolver, "PDF", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Partager les PDF"))
}
