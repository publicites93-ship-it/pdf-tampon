package fr.pdftampon.app

import android.content.Context

/** Mémorise les derniers réglages de positionnement et de paramètres. */
object Prefs {
    private const val FILE = "stamp_prefs"

    fun load(ctx: Context): StampSettings {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return StampSettings(
            position = StampPosition(
                xMm = p.getFloat("x", 10f),
                yMm = p.getFloat("y", 10f),
                widthMm = p.getFloat("w", 40f),
                heightMm = p.getFloat("h", 20f),
            ),
            opacity = p.getFloat("op", 1f),
            keepRatio = p.getBoolean("kr", true),
            suffix = p.getString("suffix", "_modifie") ?: "_modifie",
        )
    }

    fun save(ctx: Context, s: StampSettings) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat("x", s.position.xMm)
            .putFloat("y", s.position.yMm)
            .putFloat("w", s.position.widthMm)
            .putFloat("h", s.position.heightMm)
            .putFloat("op", s.opacity)
            .putBoolean("kr", s.keepRatio)
            .putString("suffix", s.suffix)
            .apply()
    }
}
