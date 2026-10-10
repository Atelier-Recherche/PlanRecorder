/*
 * Copyright 2026 PlanRecorder contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.dimowner.audiorecorder.v2.data.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafStorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun takePersistablePermission(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (e: SecurityException) {
            Timber.w(e, "Could not persist SAF tree permission")
        }
    }

    fun getRoot(treeUriString: String?): DocumentFile? {
        if (treeUriString.isNullOrBlank()) return null
        val uri = Uri.parse(treeUriString)
        return DocumentFile.fromTreeUri(context, uri)
    }

    /** Human-readable label for a SAF tree (not always a real filesystem path). */
    fun displayPath(treeUriString: String?): String {
        if (treeUriString.isNullOrBlank()) return ""
        val uri = Uri.parse(treeUriString)
        val root = DocumentFile.fromTreeUri(context, uri)
        val name = root?.name
        if (!name.isNullOrBlank()) return name
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        // e.g. "primary:Documents/Notes" → "Documents/Notes"
        return docId.substringAfter(':', docId).ifBlank { uri.toString() }
    }

    fun listDirectoryNames(dir: DocumentFile): List<String> {
        return dir.listFiles().filter { it.isDirectory }.mapNotNull { it.name }
    }

    fun ensureDir(root: DocumentFile, relativePath: String): DocumentFile? {
        if (!root.exists() || !root.canWrite()) return null
        if (relativePath.isBlank()) return root
        var current: DocumentFile = root
        for (segment in relativePath.split('/', '\\').filter { it.isNotBlank() }) {
            val existing = current.findFile(segment)
                ?: current.listFiles().firstOrNull {
                    it.isDirectory && it.name.equals(segment, ignoreCase = true)
                }
            current = when {
                existing != null && existing.isDirectory -> existing
                existing != null && existing.isFile -> {
                    Timber.w("Path segment collides with file: %s", segment)
                    return null
                }
                else -> current.createDirectory(segment) ?: return null
            }
        }
        return current
    }

    fun listFileNames(dir: DocumentFile): List<String> {
        return dir.listFiles().mapNotNull { it.name }
    }

    /**
     * Copies [localFile] into [dir] as [fileName]. Returns the created document URI, or null.
     *
     * Uses a neutral MIME + full display name so Android does not force `.oga` for Opus-in-Ogg.
     */
    fun copyFile(localFile: File, dir: DocumentFile, fileName: String, mimeType: String): Uri? {
        if (!localFile.exists()) {
            Timber.e("Local file missing: %s", localFile.absolutePath)
            return null
        }
        val uniqueName = uniqueName(dir, fileName)
        // Pass the full name with extension. Prefer octet-stream for .opus so providers
        // do not rewrite the extension to .oga (common with audio/ogg).
        val target = if (uniqueName.endsWith(".opus", ignoreCase = true)) {
            dir.createFile("application/octet-stream", uniqueName)
                ?: dir.createFile("audio/opus", uniqueName)
        } else {
            dir.createFile(mimeType, uniqueName)
                ?: dir.createFile("application/octet-stream", uniqueName)
        } ?: return null
        return try {
            context.contentResolver.openOutputStream(target.uri)?.use { out ->
                localFile.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("Cannot open output stream for ${target.uri}")
            target.uri
        } catch (e: Exception) {
            Timber.e(e, "Failed to copy to SAF")
            target.delete()
            null
        }
    }

    fun mimeForExtension(extension: String): String {
        return when (extension.lowercase()) {
            "opus" -> "audio/opus"
            "ogg", "oga" -> "audio/ogg"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "3gp" -> "audio/3gpp"
            "md" -> "text/markdown"
            else -> "application/octet-stream"
        }
    }

    /** Writes [content] into [dir] as [fileName]. Returns the document URI, or null. */
    fun writeTextFile(dir: DocumentFile, fileName: String, content: String): Uri? {
        val uniqueName = uniqueName(dir, fileName)
        val target = dir.createFile("text/markdown", uniqueName)
            ?: dir.createFile("text/plain", uniqueName)
            ?: return null
        return try {
            context.contentResolver.openOutputStream(target.uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("Cannot open output stream for ${target.uri}")
            target.uri
        } catch (e: Exception) {
            Timber.e(e, "Failed to write text to SAF")
            target.delete()
            null
        }
    }

    private fun uniqueName(dir: DocumentFile, fileName: String): String {
        if (dir.findFile(fileName) == null) return fileName
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        var i = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "$base-$i" else "$base-$i.$ext"
            if (dir.findFile(candidate) == null) return candidate
            i++
        }
    }
}
