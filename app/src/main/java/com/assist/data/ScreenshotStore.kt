package com.assist.data

import java.io.File
import java.util.UUID

/**
 * File-backed store for screenshot bytes. Screenshots are written under
 * `<baseDir>/screenshots/session_<id>/<uuid>.<ext>`; the returned path is
 * persisted in a [MediaEntity] row. Bytes never enter the database.
 *
 * [baseDir] is the app-private files dir in production (see
 * [com.assist.di.DataModule]); tests pass a temp directory.
 */
class ScreenshotStore(private val baseDir: File) {

    /** Persist [bytes] for [sessionId] and return the absolute file path. */
    fun write(sessionId: Long, bytes: ByteArray, extension: String = "png"): String {
        val dir = File(baseDir, "$SUBDIR/session_$sessionId")
        dir.mkdirs()
        val file = File(dir, "${UUID.randomUUID()}.$extension")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    /** Read the bytes at [path], or null if the file is missing. */
    fun readBytes(path: String): ByteArray? = File(path).takeIf { it.exists() }?.readBytes()

    /** Delete all screenshot files for [sessionId] (used when deleting a session). */
    fun deleteSession(sessionId: Long) {
        File(baseDir, "$SUBDIR/session_$sessionId").deleteRecursively()
    }

    private companion object {
        const val SUBDIR = "screenshots"
    }
}
