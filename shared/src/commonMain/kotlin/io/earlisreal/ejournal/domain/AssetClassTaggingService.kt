package io.earlisreal.ejournal.domain

import io.earlisreal.ejournal.data.repository.TagRepository
import io.earlisreal.ejournal.domain.model.AssetClass
import io.earlisreal.ejournal.domain.model.assetClassOf
import io.earlisreal.ejournal.domain.model.defaultTagColors

/**
 * Auto-tags closed positions "Equity" or "Options" by their symbol's shape (see [assetClassOf]).
 * Idempotent and additive: a position already carrying either tag -- whether auto-assigned earlier or
 * removed and left alone by the user -- is untouched, so this never fights a manual edit. Meant to be
 * called opportunistically wherever positions are shown (e.g. the Trade Logs screen load), so both new
 * imports and pre-existing history end up tagged without a dedicated migration step.
 */
class AssetClassTaggingService(
    private val positionTags: PositionTagService,
    private val tagRepository: TagRepository,
) {
    suspend fun ensureTagged(portfolioId: Long) {
        val positions = positionTags.forPortfolio(portfolioId)
        val untagged = positions.filter { pos ->
            pos.openingTransactionId != null && pos.tags.none { it.name == EQUITY_TAG || it.name == OPTIONS_TAG }
        }
        if (untagged.isEmpty()) return
        val byClass = untagged.groupBy { assetClassOf(it.symbol) }

        // Only create the tag(s) actually needed, so e.g. a stocks-only portfolio never gets an unused "Options" tag.
        val equityTagId = byClass[AssetClass.EQUITY]?.let { findOrCreate(EQUITY_TAG, defaultTagColors[0]) }
        val optionsTagId = byClass[AssetClass.OPTION]?.let { findOrCreate(OPTIONS_TAG, defaultTagColors[4]) }

        for (pos in untagged) {
            val tagId = if (assetClassOf(pos.symbol) == AssetClass.OPTION) optionsTagId else equityTagId
            if (tagId != null) positionTags.addTag(pos, tagId)
        }
    }

    private suspend fun findOrCreate(name: String, color: String): Long {
        tagRepository.getAll().firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.id }
        return try {
            tagRepository.create(name, color)
        } catch (e: Exception) {
            // Lost a create race (duplicate name, case-insensitive) -- use the one that won.
            tagRepository.getAll().first { it.name.equals(name, ignoreCase = true) }.id
        }
    }

    companion object {
        const val EQUITY_TAG = "Equity"
        const val OPTIONS_TAG = "Options"
    }
}
