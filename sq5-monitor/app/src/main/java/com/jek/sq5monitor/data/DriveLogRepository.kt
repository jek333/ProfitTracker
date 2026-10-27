package com.jek.sq5monitor.data

import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class DriveLogFile(val file: File) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
    val modifiedMs: Long get() = file.lastModified()
}

/** CSV drive logs stored in app-private storage (shared through the FileProvider). */
class DriveLogRepository(context: Context) {
    private val directory = File(context.filesDir, "logs").apply { mkdirs() }

    fun newLogFile(): File {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        return File(directory, "sq5-log_$stamp.csv")
    }

    fun list(): List<DriveLogFile> =
        directory.listFiles { file -> file.extension == "csv" }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map(::DriveLogFile)

    fun delete(log: DriveLogFile) {
        log.file.delete()
    }
}
