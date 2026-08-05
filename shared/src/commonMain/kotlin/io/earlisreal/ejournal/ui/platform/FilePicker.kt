package io.earlisreal.ejournal.ui.platform

/**
 * Opens the OS-native "open files" dialog filtered to broker import files (CSV / XLSX / JSON) and
 * returns the raw bytes of each chosen file. Returns an empty list if the user cancels.
 *
 * Surfaced as expect/actual (same pattern as [io.earlisreal.ejournal.ui.chart.CandlestickChart])
 * so `commonMain` stays free of platform file-dialog APIs. The JVM actual uses FileKit, which opens
 * the modern native dialog (NSOpenPanel on macOS, the Win32 COM IFileOpenDialog on Windows) with the
 * extension filter honored on both. Suspends until the dialog is dismissed; safe to call from a UI
 * coroutine.
 */
expect suspend fun pickImportFiles(): List<ByteArray>

/**
 * Reads the fixed Truthifi sync file (`~/.ejournal/truthifi-sync.json`), or null if it doesn't exist.
 * Written externally -- Claude fetches fresh transactions and writes them there whenever a sync is
 * asked for; this just reads whatever's currently there, so the app never talks to Truthifi itself
 * (it's MCP-only, reachable only from a chat session).
 */
expect suspend fun readTruthifiSyncFile(): ByteArray?
