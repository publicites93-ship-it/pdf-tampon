package fr.pdftampon.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PdfTamponApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
