package com.mapscreator.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object GarminSender {
    private const val GARMIAND_PKG = "com.garmiand"
    private const val GARMIAND_ACTIVITY = "com.garmiand.ui.MainActivity"
    private const val EXTRA_GMND_URI = "gmnd_uri"

    fun sendToGarmiand(context: Context, gmndFile: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", gmndFile)
        val intent = Intent().apply {
            setClassName(GARMIAND_PKG, GARMIAND_ACTIVITY)
            action = Intent.ACTION_SEND
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(EXTRA_GMND_URI, uri.toString())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            true
        } else {
            false
        }
    }

    fun shareGmndFile(context: Context, gmndFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", gmndFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Отправить .gmnd файл"))
    }

    fun isGarmiandInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(GARMIAND_PKG, 0)
            true
        } catch (e: Exception) {
            false
        }
}
