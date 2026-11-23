package pl.arcom.aitrackereye.util

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Dziennik aplikacji (aitrackereye_log.txt, do 4 plików po 1 MB) i plik diagnostyki - bez haseł. */
object AppLog {
    private const val MAX = 1_000_000L
    private const val KEEP = 4
    private var dir: File? = null
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun init(context: Context) {
        dir = context.filesDir
    }

    val logFile: File? get() = dir?.let { File(it, "aitrackereye_log.txt") }
    val diagnosticFile: File? get() = dir?.let { File(it, "diagnostyka.txt") }
    val backupDir: File? get() = dir?.let { File(it, "backups") }

    @Synchronized
    private fun write(level: String, msg: String) {
        Log.println(if (level == "ERROR") Log.ERROR else if (level == "WARN") Log.WARN else Log.INFO, "AiTrackerEye", msg)
        val f = logFile ?: return
        try {
            if (f.exists() && f.length() > MAX) rotate(f)
            f.appendText("${LocalDateTime.now().format(fmt)} $level $msg\n")
        } catch (_: Exception) {
        }
    }

    private fun rotate(f: File) {
        for (i in KEEP - 1 downTo 1) {
            val src = File(f.path + if (i == 1) "" else ".${i - 1}")
            val dst = File(f.path + ".$i")
            if (src.exists()) {
                dst.delete()
                src.renameTo(dst)
            }
        }
    }

    fun info(msg: String) = write("INFO", msg)
    fun warn(msg: String) = write("WARN", msg)
    fun error(msg: String, t: Throwable? = null) =
        write("ERROR", msg + (t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))

    fun saveDiagnostic(text: String) {
        try {
            diagnosticFile?.writeText(text)
        } catch (e: Exception) {
            warn("Nie zapisano diagnostyki: ${e.message}")
        }
    }
}
