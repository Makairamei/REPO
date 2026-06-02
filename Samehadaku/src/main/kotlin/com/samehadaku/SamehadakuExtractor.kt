package com.samehadaku

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.samehadaku.SamehadakuUtils.cleanEscaped
import com.samehadaku.SamehadakuUtils.fixQuality
import com.samehadaku.SamehadakuUtils.isSubtitleUrl
import com.samehadaku.SamehadakuUtils.isVideoUrl
import com.samehadaku.SamehadakuUtils.normalizeUrl
import com.samehadaku.SamehadakuUtils.originOf
import com.samehadaku.SamehadakuUtils.shouldSkipUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Base64

object SamehadakuExtractor {
    suspend fun loadLinks(
        data: String,
        mainUrl: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(data, referer = "$mainUrl/", headers = headers, timeout = 30L)
        }.getOrNull() ?: return false
        val document = response.document
        val pageText = response.text
        var found = false

        collectSubtitles(pageText, data, mainUrl, subtitleCallback)

        collectAjaxPlayers(document).forEach { player ->
            if (resolveAjaxPlayer(player, data, mainUrl, headers, subtitleCallback, callback)) {
                found = true
            }
        }

        collectIframeUrls(document, data, mainUrl, pageText).forEach { embed ->
            if (resolveExtractorOrNested(embed, data, mainUrl, headers, subtitleCallback, callback, null)) {
                found = true
            }
        }

        collectDownloadSources(document, data, mainUrl).forEach { source ->
            if (resolveExtractorOrNested(source.url, data, mainUrl, headers, subtitleCallback, callback, source.qualityName)) {
                found = true
            }
        }

        collectDirectUrls(pageText, data, mainUrl).forEach { direct ->
            if (emitDirect(direct, data, callback)) found = true
        }

        return found
    }

    private suspend fun resolveAjaxPlayer(
        player: SamehadakuAjaxPlayer,
        pageUrl: String,
        mainUrl: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ajaxUrls = SamehadakuSeeds.mirrors
            .plus(mainUrl)
            .map { it.trimEnd('/') + "/wp-admin/admin-ajax.php" }
            .distinct()
        ajaxUrls.forEach { ajaxUrl ->
            val ajaxResponse = runCatching {
                app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "player_ajax",
                        "post" to player.post,
                        "nume" to player.nume,
                        "type" to player.type
                    ),
                    referer = pageUrl,
                    headers = headers,
                    timeout = 25L
                )
            }.getOrNull() ?: return@forEach

            val ajaxText = ajaxResponse.text.cleanEscaped()
            val ajaxDocument = runCatching { ajaxResponse.document }.getOrNull() ?: Jsoup.parse(ajaxText)
            var found = false
            collectSubtitles(ajaxText, pageUrl, mainUrl, subtitleCallback)

            collectIframeUrls(ajaxDocument, pageUrl, mainUrl, ajaxText).forEach { iframe ->
                if (resolveExtractorOrNested(iframe, pageUrl, mainUrl, headers, subtitleCallback, callback, player.label)) found = true
            }
            collectDirectUrls(ajaxText, pageUrl, mainUrl).forEach { direct ->
                if (emitDirect(direct, pageUrl, callback, player.label)) found = true
            }
            if (found) return true
        }
        return false
    }

    private suspend fun resolveExtractorOrNested(
        url: String,
        referer: String,
        mainUrl: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        qualityName: String?
    ): Boolean {
        val clean = url.cleanEscaped().trim()
        if (clean.isBlank() || shouldSkipUrl(clean)) return false
        if (emitDirect(clean, referer, callback, qualityName ?: "Samehadaku")) return true

        var found = false
        runCatching {
            loadExtractor(clean, referer, subtitleCallback) { link ->
                found = true
                LicenseClient.trackActivity("Samehadaku", "PLAY", link.url)
                callback.invoke(link)
            }
        }
        if (found) return true

        if (!looksLikeEmbeddable(clean)) return false
        val response = runCatching { app.get(clean, referer = referer, headers = headers, timeout = 20L) }.getOrNull() ?: return false
        val text = response.text
        val doc = response.document
        collectSubtitles(text, clean, mainUrl, subtitleCallback)
        collectDirectUrls(text, clean, mainUrl).forEach { direct ->
            if (emitDirect(direct, clean, callback, qualityName ?: "Samehadaku")) found = true
        }
        collectIframeUrls(doc, clean, mainUrl, text).forEach { nested ->
            runCatching {
                loadExtractor(nested, clean, subtitleCallback) { link ->
                    found = true
                    LicenseClient.trackActivity("Samehadaku", "PLAY", link.url)
                    callback.invoke(link)
                }
            }
            if (!found) {
                collectDirectUrls(runCatching { app.get(nested, referer = clean, headers = headers, timeout = 15L).text }.getOrDefault(""), nested, mainUrl).forEach { direct ->
                    if (emitDirect(direct, nested, callback, qualityName ?: "Samehadaku")) found = true
                }
            }
        }
        return found
    }

    private fun collectAjaxPlayers(document: Document): List<SamehadakuAjaxPlayer> {
        val players = linkedMapOf<String, SamehadakuAjaxPlayer>()
        document.select("[data-post][data-nume], [data-post][data-type], [data-id][data-nume], li[data-post], li[data-nume], button[data-post], a[data-post]").forEach { element ->
            val post = element.attr("data-post").ifBlank { element.attr("data-id") }.ifBlank { element.attr("data-video") }
            val nume = element.attr("data-nume").ifBlank { element.attr("data-server") }.ifBlank { element.attr("data-index") }.ifBlank { "1" }
            val type = element.attr("data-type").ifBlank { "tv" }
            val label = element.text().cleanEscaped().ifBlank { element.attr("title") }.ifBlank { "Samehadaku" }
            if (post.isNotBlank()) {
                players["$post-$nume-$type"] = SamehadakuAjaxPlayer(post, nume, type, label)
            }
        }

        val html = document.html()
        Regex("""data-post=["']([^"']+)["'][^>]+data-nume=["']([^"']+)["'][^>]*(?:data-type=["']([^"']+)["'])?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { match ->
                val post = match.groupValues.getOrNull(1).orEmpty()
                val nume = match.groupValues.getOrNull(2).orEmpty().ifBlank { "1" }
                val type = match.groupValues.getOrNull(3).orEmpty().ifBlank { "tv" }
                if (post.isNotBlank()) players["$post-$nume-$type"] = SamehadakuAjaxPlayer(post, nume, type, "Samehadaku")
            }
        return players.values.toList()
    }

    private fun collectIframeUrls(document: Document, baseUrl: String, mainUrl: String, rawText: String = document.html()): List<String> {
        val urls = linkedSetOf<String>()
        document.select(
            "div.player-embed iframe, div#pembed iframe, div.iframe-server iframe, " +
                "div.responsive-embed-container iframe, main iframe[src], iframe[src], iframe[data-src], " +
                "[data-embed], [data-iframe], [data-player], [data-video], [data-url], option[value]"
        ).forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-player") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("value") }
            normalizeUrl(raw, baseUrl, mainUrl).takeIf { it.isNotBlank() && !shouldSkipUrl(it) }?.let { urls.add(it) }
        }

        val clean = rawText.cleanEscaped()
        listOf(
            Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:src|file|url|link|embed|iframe)["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s<>]+/(?:embed|e|v|d)/[^"'\s<>]+""", RegexOption.IGNORE_CASE)
        ).forEach { regex ->
            regex.findAll(clean).forEach { match ->
                val raw = match.groupValues.getOrNull(1).takeIf { !it.isNullOrBlank() } ?: match.value
                normalizeUrl(raw, baseUrl, mainUrl).takeIf { it.isNotBlank() && !shouldSkipUrl(it) }?.let { urls.add(it) }
            }
        }

        Regex("""atob\(["']([^"']+)["']\)""", RegexOption.IGNORE_CASE).findAll(rawText).forEach { match ->
            val decoded = decodeBase64(match.groupValues.getOrNull(1).orEmpty())
            collectDirectUrls(decoded, baseUrl, mainUrl).forEach { urls.add(it) }
            Regex("""https?://[^"'\s<>]+""", RegexOption.IGNORE_CASE).findAll(decoded).forEach { urlMatch ->
                normalizeUrl(urlMatch.value, baseUrl, mainUrl).takeIf { it.isNotBlank() && !shouldSkipUrl(it) }?.let { urls.add(it) }
            }
        }
        return urls.toList()
    }

    private fun collectDownloadSources(document: Document, baseUrl: String, mainUrl: String): List<SamehadakuEpisodeSource> {
        val sources = mutableListOf<SamehadakuEpisodeSource>()
        document.select(
            "div#downloadb li, div.download-eps li, div.download li, .download-eps li, " +
                ".download-server li, .mirror li, .link-download li, .download-link li"
        ).forEach { row ->
            val quality = row.select("strong, b, .quality, .res").text().ifBlank { row.ownText() }.ifBlank { row.text() }
            row.select("a[href]").forEach { anchor ->
                val link = normalizeUrl(anchor.attr("href"), baseUrl, mainUrl)
                if (link.isNotBlank() && !shouldSkipUrl(link)) {
                    val label = listOf(quality, anchor.text()).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Samehadaku" }
                    sources.add(SamehadakuEpisodeSource(link, label))
                }
            }
        }
        if (sources.isEmpty()) {
            document.select("a[href]").forEach { anchor ->
                val href = normalizeUrl(anchor.attr("href"), baseUrl, mainUrl)
                val label = anchor.text().ifBlank { anchor.attr("title") }.ifBlank { "Samehadaku" }
                if (href.isNotBlank() && !shouldSkipUrl(href) && looksLikeHoster(href)) {
                    sources.add(SamehadakuEpisodeSource(href, label))
                }
            }
        }
        return sources.distinctBy { it.url }
    }

    private fun collectDirectUrls(text: String, baseUrl: String, mainUrl: String): List<String> {
        val direct = linkedSetOf<String>()
        val clean = text.cleanEscaped()
        Regex("""https?:\\?/\\?/[^\"'\\\s<>]+""", RegexOption.IGNORE_CASE).findAll(clean).forEach { match ->
            val url = match.value.replace("\\/", "/")
            normalizeUrl(url, baseUrl, mainUrl).takeIf { isVideoUrl(it) }?.let { direct.add(it) }
        }
        Regex("""[\"'](?:file|url|src|source|video|link|hls|m3u8|mp4)[\"']\s*[:=]\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .forEach { match ->
                val url = normalizeUrl(match.groupValues.getOrNull(1), baseUrl, mainUrl)
                if (isVideoUrl(url)) direct.add(url)
            }
        Regex("""https?://[^\"'\s<>]+\.(?:m3u8|mp4|mkv|webm)(?:\?[^\"'\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .forEach { match -> direct.add(match.value.cleanEscaped()) }
        return direct.toList()
    }

    private suspend fun collectSubtitles(
        text: String,
        baseUrl: String,
        mainUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        Regex("""https?://[^\"'\s<>]+\.(?:srt|vtt|ass)(?:\?[^\"'\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text.cleanEscaped())
            .map { normalizeUrl(it.value, baseUrl, mainUrl) }
            .filter { isSubtitleUrl(it) }
            .distinct()
            .forEach { subtitleCallback.invoke(newSubtitleFile("Indonesia", it)) }
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        qualityName: String = "Samehadaku"
    ): Boolean {
        val clean = url.cleanEscaped().trim()
        if (clean.isBlank() || !isVideoUrl(clean)) return false
        val quality = qualityName.fixQuality().takeIf { it > 0 } ?: Qualities.Unknown.value
        val directHeaders = mapOf("Referer" to referer, "Origin" to originOf(referer))

        LicenseClient.trackActivity("Samehadaku", "PLAY", clean)

        return if (clean.contains(".m3u8", true)) {
            generateM3u8(
                source = "Samehadaku",
                streamUrl = clean,
                referer = referer,
                quality = quality,
                headers = directHeaders
            ).forEach(callback)
            true
        } else {
            callback.invoke(
                newExtractorLink(
                    "Samehadaku",
                    "Samehadaku ${if (quality > 0) "${quality}p" else "Direct"}",
                    clean
                ) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = directHeaders
                }
            )
            true
        }
    }

    private fun looksLikeEmbeddable(url: String): Boolean {
        val clean = url.lowercase()
        return clean.contains("/embed") || clean.contains("/e/") || clean.contains("/v/") || looksLikeHoster(clean)
    }

    private fun looksLikeHoster(url: String): Boolean {
        val clean = url.lowercase()
        return listOf(
            "blogspot", "blogger", "wibufile", "pucuk", "dailymotion", "rumble", "streamtape",
            "filemoon", "vidhide", "vidguard", "mp4upload", "mega.nz", "pixeldrain", "krakenfiles",
            "mediafire", "gofile", "gdrive", "drive.google", "acefile", "racaty", "desustream",
            "yourupload", "sendcm", "solidfiles", "filedon", "go.php", "download"
        ).any { clean.contains(it) }
    }

    private fun decodeBase64(value: String): String {
        return runCatching { String(Base64.getDecoder().decode(value)) }.getOrDefault("")
    }
}
