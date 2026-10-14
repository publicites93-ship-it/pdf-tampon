package fr.pdftampon.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class RejectedPdfException(val status: FileStatus, message: String) : Exception(message)

/**
 * Ajoute une image comme objet graphique superposé à la première page d'un PDF.
 * Le contenu existant est conservé : aucune rastérisation, aucune conversion.
 */
object PdfStamper {

    private const val PT_PER_MM = 72f / 25.4f

    /**
     * Mappe les coordonnées d'affichage (origine haut-gauche, rotation appliquée)
     * vers l'espace utilisateur PDF (origine bas-gauche, avant rotation).
     */
    private class Geo(val ox: Float, val oy: Float, val mw: Float, val mh: Float, val rot: Int) {
        val displayWidthPt: Float = if (rot == 90 || rot == 270) mh else mw
        val displayHeightPt: Float = if (rot == 90 || rot == 270) mw else mh

        fun toUser(dx: Float, dy: Float): FloatArray = when (rot) {
            90 -> floatArrayOf(ox + dy, oy + dx)
            180 -> floatArrayOf(ox + mw - dx, oy + dy)
            270 -> floatArrayOf(ox + mw - dy, oy + mh - dx)
            else -> floatArrayOf(ox + dx, oy + mh - dy)
        }

        fun toUserVector(dx: Float, dy: Float): FloatArray = when (rot) {
            90 -> floatArrayOf(dy, dx)
            180 -> floatArrayOf(-dx, dy)
            270 -> floatArrayOf(-dy, -dx)
            else -> floatArrayOf(dx, -dy)
        }
    }

    private fun geometry(page: PDPage): Geo {
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360 / 90 * 90
        if (box.width <= 0f || box.height <= 0f) {
            throw RejectedPdfException(FileStatus.INVALID, "dimensions de page invalides")
        }
        return Geo(box.lowerLeftX, box.lowerLeftY, box.width, box.height, rot)
    }

    private inline fun <T> withDoc(file: File, block: (PDDocument) -> T): T {
        val doc = try {
            FileInputStream(file).use { PDDocument.load(it) }
        } catch (e: InvalidPasswordException) {
            throw RejectedPdfException(FileStatus.PROTECTED, "PDF protégé par mot de passe : non modifié")
        } catch (e: Exception) {
            throw RejectedPdfException(FileStatus.INVALID, "PDF invalide ou corrompu")
        }
        doc.use { d ->
            if (d.isEncrypted) throw RejectedPdfException(FileStatus.PROTECTED, "PDF chiffré : non modifié")
            if (d.numberOfPages == 0) throw RejectedPdfException(FileStatus.INVALID, "PDF sans page")
            return block(d)
        }
    }

    /** Dimensions de la première page telles qu'affichées. */
    fun inspectFirstPage(file: File): PageInfo = withDoc(file) { doc ->
        val g = geometry(doc.getPage(0))
        PageInfo(g.displayWidthPt / PT_PER_MM, g.displayHeightPt / PT_PER_MM)
    }

    /**
     * Ajoute l'image à la première page et écrit le résultat dans [dst].
     * Le fichier source n'est jamais modifié. Retourne le nombre de pages vérifié.
     */
    fun stamp(src: File, dst: File, stamp: Bitmap, s: StampSettings): Int {
        val pages = withDoc(src) { doc ->
            if (doc.signatureDictionaries.isNotEmpty()) {
                throw RejectedPdfException(FileStatus.SIGNED, "PDF signé : refusé pour préserver la signature")
            }

            val page = doc.getPage(0)
            val geo = geometry(page)
            val p = s.position
            val x = p.xMm * PT_PER_MM
            val y = p.yMm * PT_PER_MM
            val w = p.widthMm * PT_PER_MM
            val h = p.heightMm * PT_PER_MM
            if (w <= 0f || h <= 0f || x < 0f || y < 0f ||
                x + w > geo.displayWidthPt + 0.01f || y + h > geo.displayHeightPt + 0.01f
            ) {
                throw RejectedPdfException(FileStatus.FAILED, "image hors de la page")
            }

            val image = LosslessFactory.createFromImage(doc, stamp)
            val gs = PDExtendedGraphicsState()
            gs.setNonStrokingAlphaConstant(s.opacity)
            gs.setStrokingAlphaConstant(s.opacity)

            // Coin bas-gauche de l'image (en affichage) et vecteurs des axes de l'image.
            val origin = geo.toUser(x, y + h)
            val axisX = geo.toUserVector(w, 0f)
            val axisY = geo.toUserVector(0f, -h)

            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.saveGraphicsState()
                cs.setGraphicsStateParameters(gs)
                cs.transform(Matrix(axisX[0], axisX[1], axisY[0], axisY[1], origin[0], origin[1]))
                cs.drawImage(image, 0f, 0f, 1f, 1f)
                cs.restoreGraphicsState()
            }

            FileOutputStream(dst).use { doc.save(it) }
            doc.numberOfPages
        }
        verify(dst, pages)
        return pages
    }

    private fun verify(file: File, expectedPages: Int) {
        withDoc(file) { d ->
            if (d.numberOfPages != expectedPages) {
                throw RejectedPdfException(FileStatus.FAILED, "vérification du PDF généré échouée")
            }
        }
    }

    /** Rendu de la première page pour l'aperçu. Ne touche pas au document. */
    fun renderFirstPage(file: File, targetWidthPx: Int): Bitmap {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val heightPx = (targetWidthPx * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(targetWidthPx, heightPx, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        }
    }
}
