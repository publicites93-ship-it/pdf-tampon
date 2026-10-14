package fr.pdftampon.app

import android.net.Uri

/** Position et taille de l'image, en millimètres, origine = coin supérieur gauche de la page affichée. */
data class StampPosition(
    val xMm: Float = 10f,
    val yMm: Float = 10f,
    val widthMm: Float = 40f,
    val heightMm: Float = 20f,
)

data class StampSettings(
    val position: StampPosition = StampPosition(),
    val opacity: Float = 1f,
    val keepRatio: Boolean = true,
    val suffix: String = "_modifie",
)

data class PdfItem(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val valid: Boolean,
)

/** Dimensions de la première page telles qu'affichées (rotation appliquée). */
data class PageInfo(val widthMm: Float, val heightMm: Float)

enum class FileStatus { OK, FAILED, INVALID, PROTECTED, SIGNED }

data class FileResult(
    val name: String,
    val status: FileStatus,
    val message: String,
    val outUri: Uri? = null,
    val outName: String? = null,
)
