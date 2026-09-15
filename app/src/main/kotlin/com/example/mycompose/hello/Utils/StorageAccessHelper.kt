package com.example.mycompose.hello.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat

/**
 * Android-safe storage/file access for the Hello app.
 *
 * - Modern Android: use the system picker (SAF) for files/folders.
 * - Android 12 and below: READ_EXTERNAL_STORAGE can be requested when needed.
 * - Optional All-files access: opens Android's special-access page. This is
 *   intentionally NOT requested automatically because Android treats it as a
 *   special/high-privilege permission.
 */
@Composable
fun rememberStorageFilePicker(
    mimeTypes: Array<String> = arrayOf("*/*"),
    onFileSelected: (Uri?) -> Unit
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        onFileSelected(uri)
    }

    return {
        launcher.launch(mimeTypes)
    }
}

@Composable
fun rememberStorageFolderPicker(
    onFolderSelected: (Uri?) -> Unit
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        onFolderSelected(uri)
    }

    return {
        launcher.launch(null)
    }
}

/** Persist access to a user-selected folder across app restarts. */
fun persistFolderAccess(context: Context, uri: Uri): Boolean {
    return try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        true
    } catch (_: SecurityException) {
        false
    }
}

fun hasStorageReadAccess(context: Context): Boolean {
    return when {
        Build.VERSION.SDK_INT >= 33 -> {
            // Media permissions are only relevant when the app actually reads
            // photos/videos/audio. SAF does not need them.
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ).any { permission ->
                ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }

        Build.VERSION.SDK_INT >= 23 -> {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

        else -> true
    }
}

@Composable
fun rememberStoragePermissionRequest(): () -> Unit {
    val permission = storagePermissionToRequest()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    return {
        permission?.let(launcher::launch)
    }
}

fun storagePermissionToRequest(): String? {
    return when {
        Build.VERSION.SDK_INT >= 33 -> Manifest.permission.READ_MEDIA_IMAGES
        Build.VERSION.SDK_INT >= 23 -> Manifest.permission.READ_EXTERNAL_STORAGE
        else -> null
    }
}

/** True only when Android's special All-files access is currently granted. */
fun hasAllFilesAccess(): Boolean {
    return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
}

/** Open Android's Special app access > All files access page for this app. */
fun openAllFilesAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= 30) {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }
}
