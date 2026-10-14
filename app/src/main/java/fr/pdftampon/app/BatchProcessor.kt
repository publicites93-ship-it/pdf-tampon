package fr.pdftampon.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Traite les PDF séquentiellement : un fichier à la fois en mémoire,
 * les échecs individuels n'interrompent pas le lot.
 */
class BatchProcessor(private val context: Context) {

    suspend fun run(
        items: List<PdfItem>,
        settings: StampSettings,
        stamp: Bitmap,
        outputTree: Uri,
        onProgress: (done: Int, total: Int, current: String) -> Unit,
        onResult: (FileResult) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val outDir = DocumentFile.fromTreeUri(context, outputTree)
                ?: throw IllegalStateException("Dossier de sortie inaccessible")
            val taken = outDir.listFiles().mapNotNull { it.name }.toMutableSet()
            val work = File(context.cacheDir, "batch").apply { mkdirs() }
            try {
                items.forEachIndexed { index, item ->
                    ensureActive()
                    onProgress(index, items.size, item.name)
                    onResult(processOne(item, settings, stamp, outDir, taken, work))
                }
            } finally {
                work.deleteRecursively()
            }
        }
    }

    private fun processOne(
        item: PdfItem,
        s: StampSettings,
        stamp: Bitmap,
        outDir: DocumentFile,
        taken: MutableSet<String>,
        work: File,
    ): FileResult {
        if (!item.valid) return FileResult(item.name, FileStatus.INVALID, "Fichier invalide ou non PDF")

        val src = File(work, "in.pdf")
        val tmp = File(work, "out.pdf")
        return try {
            FileIo.copyTo(context, item.uri, src, FileIo.MAX_PDF_BYTES)
            PdfStamper.stamp(src, tmp, stamp, s)

            val outName = uniqueName(item.name, s.suffix, taken)
            val created = outDir.createFile("application/pdf", outName)
                ?: throw IllegalStateException("création du fichier impossible")
            try {
                val out = context.contentResolver.openOutputStream(created.uri)
                    ?: throw IllegalStateException("écriture impossible")
                out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            } catch (e: Exception) {
                created.delete()
                throw e
            }
            FileResult(item.name, FileStatus.OK, "Généré", created.uri, outName)
        } catch (e: RejectedPdfException) {
            FileResult(item.name, e.status, e.message ?: "Refusé")
        } catch (e: OutOfMemoryError) {
            FileResult(item.name, FileStatus.FAILED, "Mémoire insuffisante pour ce fichier")
        } catch (e: Exception) {
            FileResult(item.name, FileStatus.FAILED, e.message ?: "Erreur de traitement")
        } finally {
            src.delete()
            tmp.delete()
        }
    }

    /** Nom "<original><suffixe>.pdf", avec " (2)", " (3)"… en cas de doublon. Jamais d'écrasement. */
    private fun uniqueName(original: String, suffix: String, taken: MutableSet<String>): String {
        val base = original.substringBeforeLast('.', original) + suffix
        var candidate = "$base.pdf"
        var i = 2
        while (candidate in taken) {
            candidate = "$base ($i).pdf"
            i++
        }
        taken.add(candidate)
        return candidate
    }
}
