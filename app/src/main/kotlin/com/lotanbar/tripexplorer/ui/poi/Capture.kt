package com.lotanbar.tripexplorer.ui.poi

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Temp files for the system camera and the audio recorder before a POI folder exists. */
object Capture {
    private fun dir(context: Context): File = File(context.cacheDir, "capture").apply { mkdirs() }

    fun newPhotoFile(context: Context): File = File(dir(context), "photo_${System.currentTimeMillis()}.jpg")
    fun newNoteFile(context: Context): File = File(dir(context), "note_${System.currentTimeMillis()}.opus")

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
