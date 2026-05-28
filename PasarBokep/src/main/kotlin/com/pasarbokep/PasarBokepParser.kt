package com.pasarbokep

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object PasarBokepParser {
    fun parseCards(document: Document, api: MainAPI): List<SearchResponse> {
        val cards = linkedMapOf<String, PasarBokepCard>()

        document.select("article, .post, .video, .item, .thumb, .loop, .entry, li").forEach { block ->
            parseBlock(block, api.mainUrl)?.let { card ->
                cards.putIfAbsent(card.url, card)
            }
        }

        document.select("main a[href], #content a[href], .site-content a[href], body a[href]").forEach { anchor ->
            parseAnchor(anchor, api.mainUrl)?.let { card ->
                cards.putIfAbsent(card.url, card)
            }
        }

        return cards.values.map { card ->
            api.newMovieSearchResponse(card.title, card.url, TvType.NSFW, fix = false) {
                this.posterUrl = card.posterUrl
            }
        }
    }

    fun parseTags(document: Document): List<String> {
        return document.select("a[href*=/category/], a[rel=category tag], .cat-links a")
            .map { PasarBokepUtils.cleanText(it.text()) }
            .filter { it.length > 2 }
            .distinct()
            .take(8)
    }

    fun parsePlot(document: Document): String? {
        val content = document.selectFirst(
            ".entry-content, .post-content, .single-content, article .content, article, main"
        ) ?: return null

        val text = content.text()
            .substringBefore("Related videos")
            .substringBefore("Bokep Terbaru")
            .substringBefore("Latest videos")
            .substringBefore("Registration is disabled")

        return PasarBokepUtils.cleanText(text).takeIf { it.length > 12 }
    }

    private fun parseBlock(block: Element, mainUrl: String): PasarBokepCard? {
        val anchor = block.selectFirst("a[href]") ?: return null
        val href = PasarBokepUtils.absoluteUrl(anchor.attr("href"), mainUrl) ?: return null
        val title = PasarBokepUtils.cleanText(
            block.selectFirst("h1, h2, h3, .entry-title, .post-title, .title")?.text()
                ?: anchor.attr("title")
                ?: anchor.text()
                ?: block.selectFirst("img")?.attr("alt")
        ).ifBlank { PasarBokepUtils.titleFromUrl(href) }

        if (!PasarBokepUtils.isLikelyVideoPage(href, title, mainUrl)) return null
        return PasarBokepCard(title = title, url = href, posterUrl = block.bestPosterFromBlock(mainUrl))
    }

    private fun parseAnchor(anchor: Element, mainUrl: String): PasarBokepCard? {
        val href = PasarBokepUtils.absoluteUrl(anchor.attr("href"), mainUrl) ?: return null
        val title = PasarBokepUtils.cleanText(
            anchor.attr("title").ifBlank { anchor.text() }.ifBlank { anchor.selectFirst("img")?.attr("alt").orEmpty() }
        ).ifBlank { PasarBokepUtils.titleFromUrl(href) }

        if (!PasarBokepUtils.isLikelyVideoPage(href, title, mainUrl)) return null
        val block = anchor.closest("article, .post, .video, .item, .thumb, .loop, .entry, li") ?: anchor
        return PasarBokepCard(title = title, url = href, posterUrl = block.bestPosterFromBlock(mainUrl))
    }

    private fun Element.bestPosterFromBlock(mainUrl: String): String? {
        return this.bestImage(mainUrl)
            ?: parent()?.bestImage(mainUrl)
            ?: selectFirst("img")?.let { PasarBokepUtils.absoluteUrl(it.attr("src"), mainUrl) }
    }
}
