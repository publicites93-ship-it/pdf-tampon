package fr.pdftampon.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

object FileIo {
    const val MAX_PDF_BYTES = 300L * 1024 * 1024
    const val MAX_IMAGE_BYTES = 30L * 1024 * 1024

    fun displayName(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "document.pdf"

    fun size(ctx: Context, uri: Uri): Long =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L

    /** Copie en lecture seule le contenu d'un Uri vers un fichier de travail, avec limite de taille. */
    fun copyTo(ctx: Context, uri: Uri, dst: File, maxBytes: Long): Long {
        var total = 0L
        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Impossible d'ouvrir le fichier")
        input.use { src ->
            dst.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IllegalStateException("Fichier trop volumineux")
                    out.write(buf, 0, n)
                }
            }
        }
        return total
    }

    /** Vérifie l'en-tête réel du fichier (et non seulement son extension). */
    fun looksLikePdf(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(5)
            input.read(head) == 5 && head.decodeToString() == "%PDF-"
        } ?: false
    } catch (e: Exception) {
        false
    }

    fun listPdfsInTree(ctx: Context, treeUri: Uri): List<DocumentFile> {
        val dir = DocumentFile.fromTreeUri(ctx, treeUri) ?: return emptyList()
        return dir.listFiles()
            .filter { it.isFile && (it.type == "application/pdf" || it.name?.endsWith(".pdf", true) == true) }
            .sortedBy { it.name?.lowercase() }
    }

    /** Décode une image PNG/JPEG en ARGB (transparence conservée), avec réduction et rotation EXIF. */
    fun decodeImage(file: File, maxSide: Int = 2400): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "fichier image illisible" }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2

        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IllegalArgumentException("fichier image illisible")

        val angle = when (ExifInterface(file.path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (angle == 0f) return raw
        val matrix = Matrix().apply { postRotate(angle) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }
}
