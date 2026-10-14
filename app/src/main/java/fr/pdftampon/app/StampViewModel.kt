package fr.pdftampon.app

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class Screen { HOME, POSITION, SETTINGS, PROCESSING, RESULTS }

data class UiState(
    val screen: Screen = Screen.HOME,
    val pdfs: List<PdfItem> = emptyList(),
    val stamp: Bitmap? = null,
    val stampName: String? = null,
    val outputTree: Uri? = null,
    val outputLabel: String? = null,
    val page: PageInfo? = null,
    val preview: Bitmap? = null,
    val settings: StampSettings = StampSettings(),
    val progress: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val results: List<FileResult> = emptyList(),
    val running: Boolean = false,
    val message: String? = null,
)

class StampViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val workDir = File(ctx.cacheDir, "vm").apply { mkdirs() }
    private val _ui = MutableStateFlow(UiState(settings = Prefs.load(ctx)))
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var batchJob: Job? = null

    // ---------- Sélection ----------

    /**
     * Demande un accès persistant en lecture. Sans cela, l'accès accordé par le sélecteur
     * peut être révoqué avant le traitement (erreur « Permission Denial » à l'aperçu).
     * Android limite le nombre d'accès persistants : au-delà, l'accès reste celui de la session.
     */
    private fun persistRead(uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Accès non persistable ou quota atteint : on continue avec l'accès de session.
        }
    }

    fun addPdfs(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            uris.forEach { persistRead(it) }
            val items = uris.map { u ->
                PdfItem(u, FileIo.displayName(ctx, u), FileIo.size(ctx, u), FileIo.looksLikePdf(ctx, u))
            }
            mergeItems(items)
        }
    }

    fun addFolder(tree: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            persistRead(tree)
            val items = FileIo.listPdfsInTree(ctx, tree).map { d ->
                PdfItem(
                    uri = d.uri,
                    name = d.name ?: "document.pdf",
                    sizeBytes = d.length(),
                    valid = FileIo.looksLikePdf(ctx, d.uri),
                )
            }
            mergeItems(items)
        }
    }

    private fun mergeItems(items: List<PdfItem>) {
        _ui.update { s -> s.copy(pdfs = (s.pdfs + items).distinctBy { it.uri }) }
    }

    fun clearPdfs() = _ui.update { it.copy(pdfs = emptyList(), page = null, preview = null) }

    fun setImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            persistRead(uri)
            try {
                val f = File(workDir, "stamp.img")
                FileIo.copyTo(ctx, uri, f, FileIo.MAX_IMAGE_BYTES)
                val bmp = FileIo.decodeImage(f)
                f.delete()
                val name = FileIo.displayName(ctx, uri)
                _ui.update { s ->
                    val ratio = bmp.height.toFloat() / bmp.width
                    val p = s.settings.position
                    val newH = if (s.settings.keepRatio) p.widthMm * ratio else p.heightMm
                    s.copy(
                        stamp = bmp,
                        stampName = name,
                        settings = s.settings.copy(position = p.copy(heightMm = newH)),
                    )
                }
            } catch (e: Exception) {
                setMessage("Image non prise en charge : ${e.message}")
            }
        }
    }

    fun setOutput(tree: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Permission non persistable : la session reste utilisable.
        }
        _ui.update {
            it.copy(outputTree = tree, outputLabel = DocumentFile.fromTreeUri(ctx, tree)?.name ?: "Dossier choisi")
        }
    }

    // ---------- Positionnement ----------

    fun openPositioning() {
        val s = _ui.value
        val first = s.pdfs.firstOrNull { it.valid }
        if (first == null) return setMessage("Sélectionnez au moins un PDF valide.")
        if (s.stamp == null) return setMessage("Choisissez d'abord une image.")

        viewModelScope.launch(Dispatchers.IO) {
            val f = File(workDir, "preview.pdf")
            try {
                FileIo.copyTo(ctx, first.uri, f, FileIo.MAX_PDF_BYTES)
                val info = PdfStamper.inspectFirstPage(f)
                val bmp = PdfStamper.renderFirstPage(f, 1200)
                _ui.update { it.copy(page = info, preview = bmp, screen = Screen.POSITION, message = null) }
            } catch (e: RejectedPdfException) {
                setMessage(e.message)
            } catch (e: Exception) {
                setMessage("Aperçu impossible : ${e.message}")
            } finally {
                f.delete()
            }
        }
    }

    private fun ratio(s: UiState): Float = s.stamp?.let { it.height.toFloat() / it.width } ?: 1f

    /** Applique la transformation puis borne la position à la page. */
    private fun updatePosition(transform: (StampPosition) -> StampPosition) {
        _ui.update { s ->
            val page = s.page ?: return@update s
            s.copy(settings = s.settings.copy(position = clampPosition(transform(s.settings.position), page)))
        }
    }

    private fun clampPosition(p: StampPosition, page: PageInfo): StampPosition {
        val w = p.widthMm.coerceIn(1f, page.widthMm)
        val h = p.heightMm.coerceIn(1f, page.heightMm)
        return p.copy(
            widthMm = w,
            heightMm = h,
            xMm = p.xMm.coerceIn(0f, page.widthMm - w),
            yMm = p.yMm.coerceIn(0f, page.heightMm - h),
        )
    }

    fun moveBy(dxMm: Float, dyMm: Float) = updatePosition { it.copy(xMm = it.xMm + dxMm, yMm = it.yMm + dyMm) }

    fun resizeBy(dwMm: Float, dhMm: Float) {
        val s = _ui.value
        val r = ratio(s)
        val keep = s.settings.keepRatio
        updatePosition { p ->
            if (keep) {
                val w = p.widthMm + dwMm
                p.copy(widthMm = w, heightMm = w * r)
            } else {
                p.copy(widthMm = p.widthMm + dwMm, heightMm = p.heightMm + dhMm)
            }
        }
    }

    fun setX(v: Float) = updatePosition { it.copy(xMm = v) }
    fun setY(v: Float) = updatePosition { it.copy(yMm = v) }

    fun setWidth(v: Float) {
        val s = _ui.value
        val r = ratio(s)
        val keep = s.settings.keepRatio
        updatePosition { p -> p.copy(widthMm = v, heightMm = if (keep) v * r else p.heightMm) }
    }

    fun setHeight(v: Float) = updatePosition { it.copy(heightMm = v) }

    // ---------- Paramètres ----------

    fun setOpacity(v: Float) = _ui.update { it.copy(settings = it.settings.copy(opacity = v.coerceIn(0.05f, 1f))) }

    fun setKeepRatio(v: Boolean) = _ui.update { s ->
        val r = ratio(s)
        val p = s.settings.position
        val newP = if (v) p.copy(heightMm = p.widthMm * r) else p
        val fixed = s.page?.let { clampPosition(newP, it) } ?: newP
        s.copy(settings = s.settings.copy(keepRatio = v, position = fixed))
    }

    fun setSuffix(v: String) = _ui.update { it.copy(settings = it.settings.copy(suffix = v.take(40))) }

    // ---------- Navigation et traitement ----------

    fun goTo(screen: Screen) {
        if (screen == Screen.SETTINGS) Prefs.save(ctx, _ui.value.settings)
        _ui.update { it.copy(screen = screen, message = null) }
    }

    fun goBack() {
        val target = when (_ui.value.screen) {
            Screen.SETTINGS -> Screen.POSITION
            Screen.POSITION, Screen.RESULTS -> Screen.HOME
            else -> null
        }
        target?.let { goTo(it) }
    }

    fun startBatch() {
        val s = _ui.value
        val stamp = s.stamp
        val tree = s.outputTree
        if (stamp == null || tree == null || s.pdfs.none { it.valid }) {
            return setMessage("Image, dossier de sortie et au moins un PDF valide sont requis.")
        }
        Prefs.save(ctx, s.settings)
        _ui.update {
            it.copy(
                screen = Screen.PROCESSING,
                running = true,
                progress = 0,
                total = s.pdfs.size,
                current = "",
                results = emptyList(),
                message = null,
            )
        }
        batchJob = viewModelScope.launch {
            try {
                BatchProcessor(ctx).run(
                    items = s.pdfs,
                    settings = s.settings,
                    stamp = stamp,
                    outputTree = tree,
                    onProgress = { done, total, cur ->
                        _ui.update { it.copy(progress = done, total = total, current = cur) }
                    },
                    onResult = { r -> _ui.update { it.copy(results = it.results + r) } },
                )
                _ui.update { it.copy(running = false, screen = Screen.RESULTS, current = "") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _ui.update {
                    it.copy(
                        running = false,
                        screen = Screen.RESULTS,
                        message = "Traitement annulé. Les fichiers déjà générés sont conservés.",
                    )
                }
                throw e
            } catch (e: Exception) {
                _ui.update {
                    it.copy(running = false, screen = Screen.RESULTS, message = "Erreur : ${e.message}")
                }
            }
        }
    }

    fun cancel() {
        batchJob?.cancel()
    }

    fun createZip() {
        val s = _ui.value
        val tree = s.outputTree ?: return
        val generated = s.results.filter { it.outUri != null }
        if (generated.isEmpty()) return setMessage("Aucun PDF généré à archiver.")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = DocumentFile.fromTreeUri(ctx, tree) ?: error("dossier de sortie inaccessible")
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())
                val zipName = "PDF_modifies_$stamp.zip"
                val zipFile = dir.createFile("application/zip", zipName) ?: error("création du ZIP impossible")
                val os = ctx.contentResolver.openOutputStream(zipFile.uri) ?: error("écriture du ZIP impossible")
                ZipOutputStream(os).use { zos ->
                    generated.forEach { r ->
                        zos.putNextEntry(ZipEntry(r.outName ?: "document.pdf"))
                        ctx.contentResolver.openInputStream(r.outUri!!)?.use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                setMessage("ZIP créé dans le dossier de sortie : $zipName")
            } catch (e: Exception) {
                setMessage("ZIP impossible : ${e.message}")
            }
        }
    }

    fun clearMessage() = setMessage(null)

    private fun setMessage(text: String?) {
        _ui.update { it.copy(message = text) }
    }

    override fun onCleared() {
        batchJob?.cancel()
        super.onCleared()
    }
}
