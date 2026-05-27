package com.nonton01

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.nonton01.Nonton01Utils.absoluteUrl
import com.nonton01.Nonton01Utils.decodeMaybe
import com.nonton01.Nonton01Utils.originOf
import com.nonton01.Nonton01Utils.videoHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.nodes.Document

object Nonton01Extractor {
    private const val TAG = "Nonton01"
    private const val MAX_HOPS = 3
    private const val MAX_DIRECT_CANDIDATES = 26
    private const val MAX_EMBED_CANDIDATES = 14
    private const val MAX_AJAX_CANDIDATES = 12

    private val keyValueRegex = Regex(
        """(?i)(?:file|src|url|source|hls|hlsUrl|video|videoUrl|stream|streamUrl|playlist|embed|iframe|link|player|content)
            .{0,16}[:=]\s*['\"]([^'\"]+)['\"]""".trimIndent().replace("\n", "")
    )
    private val quotedUrlRegex = Regex(
        """(?i)['\"]((?:https?:)?//[^'\"<>\s]+|/[^'\"<>\s]+)['\"]"""
    )
    private val iframeRegex = Regex("""(?i)<iframe[^>]+src\s*=\s*['\"]([^'\"]+)['\"]""")
    private val encodedUrlRegex = Regex("""https?%3A%2F%2F[^'\"<>\s]+""", RegexOption.IGNORE_CASE)
    private val atobRegex = Regex("""(?i)atob\s*\(\s*['\"]([A-Za-z0-9+/=_-]{16,})['\"]\s*\)""")
    private val base64StringRegex = Regex("""['\"]([A-Za-z0-9+/=]{28,})['\"]""")
    private val dataPostRegex = Regex("""(?i)data-(?:post|id|movie|movieid)\s*=\s*['\"]?(\d+)""")
    private val dataNumeRegex = Regex("""(?i)data-(?:nume|server|episode)\s*=\s*['\"]?(\d+)""")
    private val dataTypeRegex = Regex("""(?i)data-type\s*=\s*['\"]?([a-zA-Z0-9_-]+)""")
    private val jsPostRegex = Regex("""(?i)(?:post|post_id|movie_id|id)\s*[:=]\s*['\"]?(\d+)""")
    private val bodyPostRegex = Regex("""(?i)(?:postid-|post-|wp-post-)(\d{2,})""")

    private val serverAttributes = listOf(
        "src", "href", "value", "data-src", "data-url", "data-link", "data-href",
        "data-file", "data-video", "data-video-url", "data-stream", "data-stream-url",
        "data-embed", "data-iframe", "data-player", "data-play", "data-server",
        "data-content", "data-id", "data-post", "data-nume"
    )

    suspend fun loadLinks(
        providerName: String,
        mainUrl: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e(TAG, "loadLinks start: $data")
        val emitted = linkedSetOf<String>()
        for (candidate in Nonton01Utils.mirrorUrlsFor(data)) {
            val found = resolvePage(providerName, candidate, candidate, mainUrl, 0, emitted, subtitleCallback, callback)
            if (found) return true
        }
        Log.e(TAG, "loadLinks no playable links for: $data")
        return false
    }

    private suspend fun resolvePage(
        providerName: String,
        pageUrl: String,
        referer: String,
        mainUrl: String,
        depth: Int,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (depth > MAX_HOPS) {
            Log.e(TAG, "max hop reached: $pageUrl")
            return false
        }

        val response = runCatching {
            app.get(pageUrl, headers = Nonton01Utils.siteHeadersFor(referer), referer = referer)
        }.onFailure { Log.e(TAG, "GET failed $pageUrl: ${it.message}") }.getOrNull() ?: return false
        val document = response.document

        collectSubtitles(pageUrl, document, subtitleCallback)
        var found = false

        val ajaxCandidates = prioritizeCandidates(fetchAjaxPlayers(pageUrl, mainUrl, document))
        val direct = prioritizeCandidates(extractDirectMedia(pageUrl, document) + ajaxCandidates)
        val embeds = prioritizeCandidates(extractEmbeds(pageUrl, document) + ajaxCandidates.filter { looksLikeEmbed(it) })
        Log.e(TAG, "captured page=$pageUrl depth=$depth ajax=${ajaxCandidates.size} direct=${direct.size} embeds=${embeds.size}")

        for (url in direct.take(MAX_DIRECT_CANDIDATES)) {
            Log.e(TAG, "direct candidate: $url")
            val emittedNow = emitDirect(providerName, url, pageUrl, emitted, callback)
            found = found || emittedNow
            if (!emittedNow && (isKnownExtractorHost(url.lowercase()) || looksLikeEmbed(url))) {
                val extractorFound = runExtractor(url, pageUrl, emitted, subtitleCallback, callback)
                found = found || extractorFound
            }
        }

        for (embed in embeds.filterNot { direct.contains(it) }.take(MAX_EMBED_CANDIDATES)) {
            Log.e(TAG, "embed candidate: $embed referer=$pageUrl")
            val extractorFound = runExtractor(embed, pageUrl, emitted, subtitleCallback, callback)
            found = found || extractorFound
            if (!extractorFound && depth < MAX_HOPS && canRecurseInto(embed, pageUrl)) {
                val nestedFound = resolvePage(providerName, embed, pageUrl, mainUrl, depth + 1, emitted, subtitleCallback, callback)
                found = found || nestedFound
            }
        }

        if (!found) {
            Log.e(TAG, "fallback extractor on page: $pageUrl")
            found = runExtractor(pageUrl, referer, emitted, subtitleCallback, callback)
        }
        return found
    }

    private suspend fun emitDirect(
        providerName: String,
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            when {
                looksLikeHls(url) -> {
                    val links = generateM3u8(
                        source = providerName,
                        streamUrl = url,
                        referer = referer,
                        headers = videoHeaders(referer)
                    )
                    links.forEach { link ->
                        if (emitted.add(link.url)) callback(link)
                    }
                    if (links.isEmpty()) Log.e(TAG, "generateM3u8 returned empty: $url")
                    links.isNotEmpty()
                }
                looksLikeDirectMp4(url) -> {
                    if (emitted.add(url)) {
                        callback(
                            newExtractorLink(providerName, "$providerName MP4", url) {
                                this.referer = referer
                                this.quality = Qualities.Unknown.value
                                this.headers = videoHeaders(referer)
                            }
                        )
                        true
                    } else {
                        false
                    }
                }
                else -> {
                    if (!shouldTryHlsFallback(url)) return false
                    val links = runCatching {
                        generateM3u8(
                            source = providerName,
                            streamUrl = url,
                            referer = referer,
                            headers = videoHeaders(referer)
                        )
                    }.getOrDefault(emptyList())
                    links.forEach { link ->
                        if (emitted.add(link.url)) callback(link)
                    }
                    links.isNotEmpty()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "emitDirect failed $url: ${e.message}")
            false
        }
    }

    private suspend fun runExtractor(
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        return try {
            loadExtractor(url, referer, subtitleCallback) { link ->
                if (emitted.add(link.url)) {
                    found = true
                    Log.e(TAG, "loadExtractor emitted: ${link.url}")
                    callback(link)
                }
            }
            if (!found) Log.e(TAG, "loadExtractor emitted 0 links: $url")
            found
        } catch (e: Throwable) {
            Log.e(TAG, "loadExtractor failed $url: ${e.message}")
            false
        }
    }

    private suspend fun fetchAjaxPlayers(pageUrl: String, mainUrl: String, document: Document): List<String> {
        val html = normalizedHtml(document)
        val origin = originOf(pageUrl) ?: originOf(mainUrl) ?: Nonton01Seeds.MAIN_URL
        val posts = linkedSetOf<String>()
        val numes = linkedSetOf<String>()
        val types = linkedSetOf<String>()

        document.select("[data-post], [data-id], [data-nume], [data-type], .dooplay_player_option, .player-option, .server, .server-item").forEach { element ->
            listOf("data-post", "data-id", "data-movie", "data-movieid").map { element.attr(it) }.filter { it.isNotBlank() && it.all { ch -> ch.isDigit() } }.forEach { posts.add(it) }
            listOf("data-nume", "data-server", "data-episode").map { element.attr(it) }.filter { it.isNotBlank() && it.all { ch -> ch.isDigit() } }.forEach { numes.add(it) }
            element.attr("data-type").takeIf { it.isNotBlank() }?.let { types.add(it) }
        }
        dataPostRegex.findAll(html).map { it.groupValues[1] }.forEach { posts.add(it) }
        dataNumeRegex.findAll(html).map { it.groupValues[1] }.forEach { numes.add(it) }
        dataTypeRegex.findAll(html).map { it.groupValues[1] }.forEach { types.add(it) }
        jsPostRegex.findAll(html).map { it.groupValues[1] }.take(3).forEach { posts.add(it) }
        bodyPostRegex.findAll(html).map { it.groupValues[1] }.take(4).forEach { posts.add(it) }

        if (posts.isEmpty()) return emptyList()
        if (numes.isEmpty()) (1..8).map { it.toString() }.forEach { numes.add(it) }
        if (types.isEmpty()) listOf("movie", "tv", "episode").forEach { types.add(it) }

        val endpoints = listOf(
            "$origin/wp-admin/admin-ajax.php",
            "$origin/admin-ajax.php",
            "$origin/wp-json/dooplay/v1/player"
        )
        val payloads = mutableListOf<Triple<String, String, String>>()
        posts.take(2).forEach { post ->
            numes.take(8).forEach { nume ->
                types.take(3).forEach { type -> payloads.add(Triple(post, nume, type)) }
            }
        }

        val out = linkedSetOf<String>()
        var attempts = 0
        for ((post, nume, type) in payloads) {
            if (attempts >= MAX_AJAX_CANDIDATES) break
            for (endpoint in endpoints) {
                if (attempts >= MAX_AJAX_CANDIDATES) break
                attempts++
                val actions = listOf("doo_player_ajax", "dt_player_ajax", "player_ajax")
                for (action in actions) {
                    val form = "action=$action&post=$post&nume=$nume&type=$type"
                    Log.e(TAG, "ajax probe: $endpoint action=$action post=$post nume=$nume type=$type")
                    val responseText = runCatching {
                        val body = form.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull())
                        app.post(
                            endpoint,
                            requestBody = body,
                            headers = Nonton01Utils.siteHeadersFor(pageUrl) + mapOf(
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                "Referer" to pageUrl,
                                "X-Requested-With" to "XMLHttpRequest"
                            )
                        ).text
                    }.getOrNull() ?: runCatching {
                        app.get(
                            "$endpoint?$form",
                            headers = Nonton01Utils.siteHeadersFor(pageUrl) + mapOf(
                                "Referer" to pageUrl,
                                "X-Requested-With" to "XMLHttpRequest"
                            )
                        ).text
                    }.getOrNull()

                    if (!responseText.isNullOrBlank()) {
                        extractUrlsFromText(pageUrl, ajaxPayloadText(responseText)).forEach { out.add(it) }
                        if (out.isNotEmpty()) Log.e(TAG, "ajax captured ${out.size} candidates from $endpoint action=$action")
                    }
                    if (out.any { looksLikeHls(it) || looksLikeDirectMp4(it) || isKnownExtractorHost(it.lowercase()) || looksLikeEmbed(it) }) break
                }
                if (out.any { looksLikeHls(it) || looksLikeDirectMp4(it) || isKnownExtractorHost(it.lowercase()) || looksLikeEmbed(it) }) break
            }
            if (out.any { looksLikeHls(it) || looksLikeDirectMp4(it) || isKnownExtractorHost(it.lowercase()) || looksLikeEmbed(it) }) break
        }
        return out.toList()
    }

    private fun ajaxPayloadText(raw: String): String {
        val normalized = raw
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
        return runCatching {
            val obj = JSONObject(normalized)
            listOf("embed_url", "url", "link", "src", "file", "html", "iframe", "content", "data")
                .mapNotNull { key -> obj.optString(key).takeIf { it.isNotBlank() && it != "null" } }
                .joinToString("\n")
                .ifBlank { normalized }
        }.getOrDefault(normalized)
    }

    private suspend fun collectSubtitles(pageUrl: String, document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val url = absoluteUrl(pageUrl, element.attr("src").ifBlank { element.attr("href") }) ?: return@forEach
            val label = Nonton01Utils.cleanText(element.attr("label").ifBlank { element.text().ifBlank { "Subtitle" } })
            runCatching { subtitleCallback(newSubtitleFile(label, url)) }
        }
    }

    private fun extractDirectMedia(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        extractAttributeUrls(pageUrl, document).filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
        extractUrlsFromText(pageUrl, normalizedHtml(document)).filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
        return out.distinct()
    }

    private fun extractEmbeds(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        extractAttributeUrls(pageUrl, document).filter { looksLikeEmbed(it) }.forEach { out.add(it) }
        extractUrlsFromText(pageUrl, normalizedHtml(document)).filter { looksLikeEmbed(it) }.forEach { out.add(it) }
        return out.distinct()
    }

    private fun extractUrlsFromText(pageUrl: String, text: String): List<String> {
        val html = normalizedText(text)
        val out = linkedSetOf<String>()
        keyValueRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.forEach { out.add(it) }
        quotedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.forEach { out.add(it) }
        iframeRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.forEach { out.add(it) }
        encodedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.value) }.forEach { out.add(it) }
        decodeBase64Candidates(html).mapNotNull { normalizeUrl(pageUrl, it) }.forEach { out.add(it) }
        return out.distinct()
    }

    private fun extractAttributeUrls(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        document.select("iframe, embed, video, source, option, a[href], button, div, span, li, script").forEach { element ->
            serverAttributes.forEach { attr ->
                val raw = element.attr(attr)
                normalizeUrl(pageUrl, raw)?.let { out.add(it) }
                if (raw.contains("http", ignoreCase = true) ||
                    raw.contains("iframe", ignoreCase = true) ||
                    raw.contains("&lt;", ignoreCase = true) ||
                    raw.contains("\\/")) {
                    extractUrlsFromText(pageUrl, raw).forEach { out.add(it) }
                }
            }
            if (element.tagName().equals("script", ignoreCase = true)) {
                extractUrlsFromText(pageUrl, element.data()).forEach { out.add(it) }
            }
        }
        return out.distinct()
    }

    private fun normalizedHtml(document: Document): String = normalizedText(document.html())

    private fun normalizedText(text: String): String = text
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\'", "'")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003a", ":")
        .replace("\\u002f", "/")

    private fun normalizeUrl(pageUrl: String, value: String?): String? {
        val raw = decodeMaybe(value.orEmpty())
            .trim()
            .trim('"', '\'', ',', ';', ' ')
        if (raw.isBlank()) return null
        val low = raw.lowercase()
        if (low == "#" || low == "null" || low == "undefined" || low == "about:blank") return null
        if (low.startsWith("javascript:") || low.startsWith("data:") || low.startsWith("blob:") || low.startsWith("intent:")) return null
        if (low.contains(".jpg") || low.contains(".png") || low.contains(".webp") || low.contains(".gif")) return null
        if (low.contains("youtube.com") || low.contains("youtu.be") || low.contains("trailer")) return null
        if (isDeniedUrl(low)) return null
        return absoluteUrl(pageUrl, raw)
    }

    private fun decodeBase64Candidates(html: String): List<String> {
        val out = linkedSetOf<String>()
        val candidates = mutableListOf<String>()
        atobRegex.findAll(html).map { it.groupValues[1] }.forEach { candidates.add(it) }
        base64StringRegex.findAll(html).map { it.groupValues[1] }.take(120).forEach { candidates.add(it) }
        candidates.forEach { encoded ->
            val decoded = decodeBase64(encoded) ?: return@forEach
            if (decoded.contains("http", ignoreCase = true) || decoded.contains("iframe", ignoreCase = true) || decoded.contains("m3u8", ignoreCase = true)) {
                keyValueRegex.findAll(decoded).map { it.groupValues[1] }.forEach { out.add(it) }
                quotedUrlRegex.findAll(decoded).map { it.groupValues[1] }.forEach { out.add(it) }
                iframeRegex.findAll(decoded).map { it.groupValues[1] }.forEach { out.add(it) }
                encodedUrlRegex.findAll(decoded).map { it.value }.forEach { out.add(it) }
                if (decoded.startsWith("http", ignoreCase = true)) out.add(decoded)
            }
        }
        return out.distinct()
    }

    private fun decodeBase64(value: String): String? {
        return runCatching {
            val normalized = value.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(Base64.decode(padded, Base64.DEFAULT))
        }.getOrNull()
    }

    private fun looksLikeHls(url: String): Boolean {
        val low = url.lowercase()
        return low.contains(".m3u8") || low.contains("m3u8") || low.contains("playlist") || low.contains("master.m3u")
    }

    private fun looksLikeDirectMp4(url: String): Boolean {
        val low = url.lowercase()
        return low.contains(".mp4") || low.contains("googlevideo") || low.contains("videoplayback")
    }

    private fun looksLikeMediaOrPlayer(url: String): Boolean {
        val low = url.lowercase()
        return !isDeniedUrl(low) &&
            (looksLikeHls(url) ||
                looksLikeDirectMp4(url) ||
                isKnownExtractorHost(low) ||
                looksLikeEmbed(url) ||
                shouldTryHlsFallback(url))
    }

    private fun looksLikeEmbed(url: String): Boolean {
        val low = url.lowercase()
        return !isDeniedUrl(low) &&
            !looksLikeDirectMp4(url) &&
            !looksLikeHls(url) &&
            (isKnownExtractorHost(low) ||
                low.contains("/embed/") ||
                low.contains("/player/") ||
                low.contains("?embed=") ||
                low.contains("?source=") ||
                low.contains("?url="))
    }

    private fun shouldTryHlsFallback(url: String): Boolean {
        val low = url.lowercase()
        if (isDeniedUrl(low) || looksLikeDirectMp4(url) || looksLikeEmbed(url)) return false
        return low.contains("m3u") ||
            low.contains("playlist") ||
            low.contains("hls") ||
            low.contains("master") ||
            low.contains("chunklist") ||
            low.contains("manifest") ||
            low.contains("index.php?source=") ||
            low.contains("index.php?url=")
    }

    private fun isKnownExtractorHost(low: String): Boolean {
        return listOf(
            "myvidplay", "minochinos", "hglink", "streamtape", "dood", "filemoon",
            "vidhide", "vidguard", "filelions", "streamwish", "streamsb", "sbembed",
            "voe.sx", "uqload", "mixdrop", "fembed", "doodstream", "streamlare",
            "luluvdo", "vidmoly", "wolfstream", "upstream", "streamruby", "mcloud"
        ).any { low.contains(it) }
    }

    private fun isDeniedUrl(low: String): Boolean {
        return low.contains("facebook.com") ||
            low.contains("twitter.com") ||
            low.contains("instagram.com") ||
            low.contains("whatsapp") ||
            low.contains("telegram") ||
            low.contains("disqus") ||
            low.contains("googletagmanager") ||
            low.contains("google-analytics") ||
            low.contains("doubleclick") ||
            low.contains("/wp-content/themes/") ||
            low.endsWith(".css") ||
            low.endsWith(".js")
    }

    private fun canRecurseInto(embed: String, pageUrl: String): Boolean {
        val embedOrigin = originOf(embed)
        val pageOrigin = originOf(pageUrl)
        val low = embed.lowercase()
        return embedOrigin != null &&
            embedOrigin != pageOrigin &&
            !Nonton01Utils.isSameHost(embed) &&
            !isDeniedUrl(low) &&
            (isKnownExtractorHost(low) || low.contains("/embed/") || low.contains("/player/"))
    }

    private fun prioritizeCandidates(urls: List<String>): List<String> {
        return urls.distinct().sortedBy { url ->
            when {
                looksLikeHls(url) -> 0
                looksLikeDirectMp4(url) -> 1
                isKnownExtractorHost(url.lowercase()) -> 2
                looksLikeEmbed(url) -> 3
                else -> 4
            }
        }
    }
}
