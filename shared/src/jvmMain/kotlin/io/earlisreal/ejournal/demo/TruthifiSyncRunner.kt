package io.earlisreal.ejournal.demo

import io.earlisreal.ejournal.data.SqlDelightPortfolioRepository
import io.earlisreal.ejournal.data.SqlDelightTransactionRepository
import io.earlisreal.ejournal.data.database.JvmDatabaseFactory
import io.earlisreal.ejournal.domain.parser.TruthifiJsonParser
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Headless sync: `generate-csv sync-truthifi <portfolio name> <truthifi-export.json>`. Runs a
 * Claude-pulled Truthifi `get_transactions` export through [TruthifiJsonParser] and inserts into the
 * real local DB, reusing the same dedup (`insert()` returns null on duplicate externalId) every CSV
 * import already relies on. No GUI involved -- this is the "one-sentence sync, on request" workflow:
 * Claude pulls fresh Truthifi data, writes it to a file, then runs this against ~/.ejournal/ejournal.db.
 */
fun runTruthifiSync(args: Array<String>) {
    if (args.size < 2) {
        println("usage: sync-truthifi <portfolio name> <path-to-truthifi-export.json>")
        return
    }
    val portfolioName = args[0]
    val file = File(args[1])
    if (!file.exists()) {
        println("File not found: ${file.absolutePath}")
        return
    }

    val db = JvmDatabaseFactory.create()
    val portfolioRepository = SqlDelightPortfolioRepository(db)
    val transactionRepository = SqlDelightTransactionRepository(db)
    val parser = TruthifiJsonParser()

    runBlocking {
        val portfolio = portfolioRepository.getAll().find { it.name.equals(portfolioName, ignoreCase = true) }
        if (portfolio == null) {
            println("No portfolio named \"$portfolioName\". Existing portfolios:")
            portfolioRepository.getAll().forEach { println("  - ${it.name}") }
            return@runBlocking
        }

        val content = file.readBytes()
        if (!parser.detect(content)) {
            println("File doesn't look like a Truthifi transactions export: ${file.absolutePath}")
            return@runBlocking
        }

        val result = parser.parse(content, portfolio.id)
        val inserted = result.transactions.count { transactionRepository.insert(it) != null }
        val duplicates = result.transactions.size - inserted

        println("Parsed ${result.transactions.size} transaction(s) from ${file.name}")
        println("Inserted: $inserted, duplicates skipped: $duplicates, non-trade skipped: ${result.skipped.nonTrade}, unparsed: ${result.skipped.unparsed}")
    }
}
