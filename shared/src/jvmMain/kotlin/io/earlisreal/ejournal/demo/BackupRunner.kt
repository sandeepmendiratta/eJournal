package io.earlisreal.ejournal.demo

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val KEEP_LOCAL = 10

/**
 * Headless on-request DB backup: `generate-csv backup`. Uses sqlite3's `.backup` dot-command (the
 * SQLite online backup API) rather than a raw file copy, so a snapshot taken while the app is running
 * (WAL mode, concurrent writes) is never a half-written/corrupt copy. Writes one timestamped copy into
 * ~/.ejournal/backups/ (pruned to the most recent [KEEP_LOCAL]) and, if the folder is present, a second
 * copy into the user's Google Drive for off-machine safety. On request only -- no background schedule.
 */
fun runBackup(args: Array<String>) {
    val home = File(System.getProperty("user.home"))
    val source = File(home, ".ejournal/ejournal.db")
    if (!source.exists()) {
        println("No database found at ${source.absolutePath}")
        return
    }

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
    val filename = "ejournal-$stamp.db"

    val localDir = File(home, ".ejournal/backups").apply { mkdirs() }
    val localTarget = File(localDir, filename)
    if (!sqliteBackup(source, localTarget)) {
        println("Backup failed -- is the 'sqlite3' command available on PATH?")
        return
    }
    println("Local backup: ${localTarget.absolutePath} (${sizeMb(localTarget)} MB)")
    pruneOldBackups(localDir)

    val googleDrive = File(home, "Library/CloudStorage/GoogleDrive-sandeepmendiratta@gmail.com/My Drive")
    if (googleDrive.exists()) {
        val cloudDir = File(googleDrive, "eJournal-backups").apply { mkdirs() }
        val cloudTarget = File(cloudDir, filename)
        if (sqliteBackup(source, cloudTarget)) {
            println("Cloud backup: ${cloudTarget.absolutePath}")
        } else {
            println("Cloud backup failed -- local backup still succeeded.")
        }
    } else {
        println("Google Drive folder not found at ${googleDrive.absolutePath} -- skipped cloud copy.")
    }
}

private fun sqliteBackup(source: File, dest: File): Boolean = runCatching {
    val process = ProcessBuilder("sqlite3", source.absolutePath, ".backup '${dest.absolutePath}'")
        .redirectErrorStream(true)
        .start()
    val exitCode = process.waitFor()
    exitCode == 0 && dest.exists()
}.getOrDefault(false)

private fun pruneOldBackups(dir: File) {
    val backups = dir.listFiles { f -> f.name.startsWith("ejournal-") && f.name.endsWith(".db") }
        ?.sortedByDescending { it.name } ?: return
    backups.drop(KEEP_LOCAL).forEach { db ->
        db.delete()
        // A backup normally lands as a single file, but opening it later with WAL enabled (e.g. a
        // sanity-check query) leaves -wal/-shm siblings that won't match the ".db" glob above.
        File(dir, "${db.name}-wal").delete()
        File(dir, "${db.name}-shm").delete()
    }
}

private fun sizeMb(file: File): String = "%.1f".format(file.length() / 1024.0 / 1024.0)
