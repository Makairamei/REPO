package com.cgvindo

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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import org.jsoup.nodes.Document

object CgvIndoExtractor {
    private const val TAG = "CgvIndo"
    private const val MAX_HOPS = 3
    private const val MAX_DIRECT = 24
    private const val MAX_EMBED = 18
    private const val MAX_AJAX_PROBES = 36

    private val keyValueRegex = Regex(
        """(?i)(?:file|src|url|source|hls|hlsUrl|video|videoUrl|stream|streamUrl|playlist|embed|iframe|link|fileUrl)\s*[:=]\s*['\"]([^'\"]+)['\"]"""
    )
    private val quotedUrlRegex = Regex("""(?i)['\"]((?:https?:)?//[^'\"<>\s]+|/[^'\"<>\s]+)['\"]""")
    private val iframeRegex = Regex("""(?i)<iframe[^>]+src\s*=\s*['\"]([^'\"]+)['\"]""")
    private val encodedUrlRegex = Regex("""https?%3A%2F%2F[^'\"<>\s]+""", RegexOption.IGNORE_CASE)
    private val atobRegex = Regex("""(?i)atob\s*\(\s*['\"]([A-Za-z0-9+/=_-]{16,})['\"]\s*\)""")
    private val base64StringRegex = Regex("""['\"]([A-Za-z0-9+/=]{28,})['\"]""")

    private val optionSelectors = listOf(
        ".dooplay_player_option", "#playeroptionsul li", "ul#playeroptionsul li", ".player_sorces li",
        ".player_sources li", ".server-item", ".server a", ".mirror a", ".player option", "option"
    ).joinToString(",")

    private val serverAttributes = listOf(
        "src", "href", "value", "data-src", "data-lazy-src", "data-url", "data-link", "data-href",
        "data-file", "data-video", "data-video-url", "data-stream", "data-stream-url", "data-hls", "data-m3u8",
        "data-embed", "data-iframe", "data-player", "data-play", "data-server", "data-html", "data-frame", "data-code"
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
        val found = resolvePage(providerName, mainUrl, data, data, 0, emitted, subtitleCallback, callback)
        if (!found) Log.e(TAG, "loadLinks no playable links for: $data")
        return found
    }

    private suspend fun resolvePage(
        providerName: String,
        mainUrl: String,
        pageUrl: String,
        referer: String,
        depth: Int,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (depth > MAX_HOPS) return false
        val document = runCatching {
            app.get(pageUrl, headers = CgvIndoUtils.siteHeaders, referer = referer).document
        }.onFailure { Log.e(TAG, "GET failed $pageUrl: ${it.message}") }.getOrNull() ?: return false

        collectSubtitles(pageUrl, document, subtitleCallback)
        val ajaxCandidates = fetchAjaxPlayers(pageUrl, document)
        val direct = prioritizeCandidates(extractDirectMedia(pageUrl, document) + ajaxCandidates.filter { looksLikeDirectOrHls(it) })
        val embeds = prioritizeCandidates(extractEmbeds(pageUrl, document) + ajaxCandidates.filter { looksLikeEmbed(it) || !looksLikeDirectOrHls(it) })
        Log.e(TAG, "captured page=$pageUrl depth=$depth ajax=${ajaxCandidates.size} direct=${direct.size} embeds=${embeds.size}")

        var found = false
        for (url in direct.take(MAX_DIRECT)) {
            Log.e(TAG, "direct candidate: $url")
            val emittedNow = emitDirect(providerName, url, pageUrl, emitted, callback)
            found = found || emittedNow
            if (!emittedNow) found = runExtractor(url, pageUrl, emitted, subtitleCallback, callback) || found
        }

        for (embed in embeds.filterNot { direct.contains(it) }.take(MAX_EMBED)) {
            Log.e(TAG, "embed candidate: $embed")
            val extractorFound = runExtractor(embed, pageUrl, emitted, subtitleCallback, callback)
            found = found || extractorFound
            if (!extractorFound && canRecurseInto(embed, pageUrl, depth)) {
                found = resolvePage(providerName, mainUrl, embed, pageUrl, depth + 1, emitted, subtitleCallback, callback) || found
            }
        }

        if (!found) found = runExtractor(pageUrl, referer, emitted, subtitleCallback, callback)
        return found
    }

    private suspend fun fetchAjaxPlayers(pageUrl: String, document: Document): List<String> {
        val endpoints = ajaxEndpoints(pageUrl, document)
        val options = playerOptions(pageUrl, document)
        if (options.isEmpty()) Log.e(TAG, "AJAX no player options found")
        val out = linkedSetOf<String>()
        val actions = listOf("doo_player_ajax", "dt_player_ajax", "player_ajax", "get_player", "get_video", "get_embed", "getEmbed")
        val fallbackTypes = inferTypes(pageUrl)
        var probes = 0

        for (endpoint in endpoints) {
            for (option in options) {
                val postValues = listOfNotNull(option.post, option.server).distinct().ifEmpty { listOfNotNull(option.post) }
                val numeValues = listOfNotNull(option.nume, option.server).distinct().ifEmpty { listOfNotNull(option.nume) }
                val typeValues = listOfNotNull(option.type).ifEmpty { fallbackTypes }
                for (action in actions) {
                    for (post in postValues.ifEmpty { listOf("") }) {
                        for (nume in numeValues.ifEmpty { listOf("1") }) {
                            for (type in typeValues) {
                                if (post.isBlank()) continue
                                if (probes++ >= MAX_AJAX_PROBES) return out.toList()
                                val forms = ajaxBodies(action, post, nume, type, option)
                                forms.forEach { body ->
                                    val response = runCatching {
                                        val encodedBody = body.entries.joinToString("&") { (key, value) -> "${key}=${URLEncoder.encode(value, "UTF-8")}" }
                                        val requestBody = encodedBody.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull())
                                        val res = app.post(endpoint, requestBody = requestBody, headers = CgvIndoUtils.ajaxHeaders(pageUrl), referer = pageUrl)
                                        Log.e(TAG, "AJAX endpoint=$endpoint action=$action post=$post nume=$nume type=$type status=${res.code} body=${res.text.take(300)}")
                                        res.text
                                    }.getOrNull() ?: return@forEach
                                    ajaxPayloadUrls(pageUrl, response).forEach { out.add(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
        Log.e(TAG, "AJAX captured candidates=${out.size}")
        return out.toList()
    }

    private fun ajaxBodies(action: String, post: String, nume: String, type: String, option: CgvIndoPlayerOption): List<Map<String, String>> {
        val base = linkedMapOf("action" to action, "post" to post, "nume" to nume, "type" to type)
        val alt = linkedMapOf("action" to action, "id" to post, "server" to nume, "type" to type)
        val alt2 = linkedMapOf("action" to action, "post_id" to post, "server" to nume, "episode" to nume, "type" to type)
        option.server?.let { base["server"] = it }
        return listOf(base, alt, alt2)
    }

    private fun ajaxEndpoints(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        val origin = CgvIndoUtils.originOf(pageUrl) ?: CgvIndoSeeds.MAIN_URL
        out.add("$origin/wp-admin/admin-ajax.php")
        val html = normalizedHtml(document)
        Regex("(?i)(?:ajaxurl|admin_ajax|adminAjax|ajax_url)\\s*[:=]\\s*['\"]([^'\"]+)['\"]").findAll(html)
            .mapNotNull { CgvIndoUtils.absoluteUrl(pageUrl, it.groupValues[1]) }
            .forEach { out.add(it) }
        document.select("[data-ajax], [data-ajaxurl], form[action]").forEach { el ->
            listOf("data-ajax", "data-ajaxurl", "action").forEach { attr -> CgvIndoUtils.absoluteUrl(pageUrl, el.attr(attr))?.let { out.add(it) } }
        }
        return out.toList()
    }

    private fun playerOptions(pageUrl: String, document: Document): List<CgvIndoPlayerOption> {
        val out = mutableListOf<CgvIndoPlayerOption>()
        document.select(optionSelectors).forEach { el ->
            val attrs = listOf(el.attr("data-post"), el.attr("data-id"), el.attr("data-movie"), el.attr("data-movieid"), el.attr("data-postid"))
            val post = attrs.firstOrNull { it.isNotBlank() }
            val nume = listOf(el.attr("data-nume"), el.attr("data-server"), el.attr("data-episode"), el.attr("value")).firstOrNull { it.isNotBlank() }
            val type = listOf(el.attr("data-type"), el.attr("data-player"), el.attr("data-kind")).firstOrNull { it.isNotBlank() }
            val idClass = "${el.id()} ${el.className()} ${el.attr("onclick")}" 
            val fallback = Regex("(?i)(?:player-option|dooplay_player_option|player_option)[-_]?(\\d+)[-_]?(\\d+)?").find(idClass)
            val fPost = post ?: fallback?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            val fNume = nume ?: fallback?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() } ?: el.attr("data-server").takeIf { it.isNotBlank() }
            if (!fPost.isNullOrBlank() || !fNume.isNullOrBlank()) {
                out.add(CgvIndoPlayerOption(fPost, fNume ?: "1", type, el.attr("data-server").takeIf { it.isNotBlank() }, CgvIndoUtils.cleanText(el.text())))
            }
        }
        if (out.isEmpty()) {
            val html = normalizedHtml(document)
            val post = Regex("(?i)post\\s*[:=]\\s*['\"]?(\\d+)").find(html)?.groupValues?.getOrNull(1)
                ?: Regex("(?i)post_id\\s*[:=]\\s*['\"]?(\\d+)").find(html)?.groupValues?.getOrNull(1)
            if (!post.isNullOrBlank()) out.add(CgvIndoPlayerOption(post, "1", inferTypes(pageUrl).firstOrNull()))
        }
        return out.distinctBy { "${it.post}|${it.nume}|${it.type}|${it.server}" }
    }

    private fun inferTypes(pageUrl: String): List<String> {
        val low = pageUrl.lowercase()
        return when {
            "/episode" in low || "episode" in low -> listOf("tv", "episode", "movie")
            "/series" in low || "series" in low -> listOf("tv", "episode", "movie")
            else -> listOf("movie", "tv", "episode")
        }
    }

    private fun ajaxPayloadUrls(pageUrl: String, response: String): List<String> {
        val out = linkedSetOf<String>()
        val decoded = CgvIndoUtils.decodeMaybe(response)
        fun collect(text: String) {
            normalizeUrl(pageUrl, text)?.let { if (looksLikeMediaOrPlayer(it) || looksLikeEmbed(it)) out.add(it) }
            extractUrlsFromHtml(pageUrl, text).forEach { out.add(it) }
        }
        collect(decoded)
        runCatching { JSONObject(decoded) }.getOrNull()?.let { json ->
            collectJson(pageUrl, json, out)
        }
        return out.toList()
    }

    private fun collectJson(pageUrl: String, json: JSONObject, out: MutableSet<String>) {
        json.keys().forEach { key ->
            val value = json.opt(key)
            when (value) {
                is String -> ajaxPayloadUrls(pageUrl, value).forEach { out.add(it) }
                is JSONObject -> collectJson(pageUrl, value, out)
            }
        }
    }

    private suspend fun emitDirect(providerName: String, url: String, referer: String, emitted: MutableSet<String>, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            when {
                looksLikeHls(url) -> {
                    val links = generateM3u8(source = providerName, streamUrl = url, referer = referer, headers = CgvIndoUtils.videoHeaders(referer))
                    links.forEach { link -> if (emitted.add(link.url)) callback(link) }
                    links.isNotEmpty()
                }
                looksLikeDirectMp4(url) -> {
                    if (emitted.add(url)) {
                        callback(newExtractorLink(providerName, "$providerName MP4", url) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                            this.headers = CgvIndoUtils.videoHeaders(referer)
                        })
                        true
                    } else false
                }
                shouldProbeAsHls(url) -> {
                    val links = runCatching { generateM3u8(source = providerName, streamUrl = url, referer = referer, headers = CgvIndoUtils.videoHeaders(referer)) }.getOrDefault(emptyList())
                    links.forEach { link -> if (emitted.add(link.url)) callback(link) }
                    links.isNotEmpty()
                }
                else -> false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "emitDirect failed $url: ${e.message}")
            false
        }
    }

    private suspend fun runExtractor(url: String, referer: String, emitted: MutableSet<String>, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        return try {
            loadExtractor(url, referer, subtitleCallback) { link ->
                if (emitted.add(link.url)) {
                    found = true
                    Log.e(TAG, "loadExtractor emitted: ${link.url}")
                    callback(link)
                }
            }
            found
        } catch (e: Throwable) {
            Log.e(TAG, "loadExtractor failed $url: ${e.message}")
            false
        }
    }

    private suspend fun collectSubtitles(pageUrl: String, document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val url = CgvIndoUtils.absoluteUrl(pageUrl, element.attr("src").ifBlank { element.attr("href") }) ?: return@forEach
            val label = CgvIndoUtils.cleanText(element.attr("label").ifBlank { element.text().ifBlank { "Subtitle" } })
            runCatching { subtitleCallback(newSubtitleFile(label, url)) }
        }
    }

    private fun extractDirectMedia(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        extractAttributeUrls(pageUrl, document).filter { looksLikeDirectOrHls(it) || shouldProbeAsHls(it) }.forEach { out.add(it) }
        val html = normalizedHtml(document)
        keyValueRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
        quotedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksLikeDirectOrHls(it) }.forEach { out.add(it) }
        iframeRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksLikeDirectOrHls(it) }.forEach { out.add(it) }
        encodedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.value) }.filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
        decodeBase64Candidates(html).mapNotNull { normalizeUrl(pageUrl, it) }.filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
        return out.distinct()
    }

    private fun extractEmbeds(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        extractAttributeUrls(pageUrl, document).filter { looksLikeEmbed(it) }.forEach { out.add(it) }
        val html = normalizedHtml(document)
        quotedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksLikeEmbed(it) }.forEach { out.add(it) }
        iframeRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksLikeEmbed(it) }.forEach { out.add(it) }
        keyValueRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksLikeEmbed(it) }.forEach { out.add(it) }
        decodeBase64Candidates(html).mapNotNull { normalizeUrl(pageUrl, it) }.filter { looksLikeEmbed(it) }.forEach { out.add(it) }
        return out.distinct()
    }

    private fun extractUrlsFromHtml(pageUrl: String, html: String): List<String> {
        val out = linkedSetOf<String>()
        val doc = Jsoup.parseBodyFragment(html, pageUrl)
        doc.select("iframe[src], a[href], source[src], video[src], [data-src], [data-url], [data-file], [data-embed], [data-hls], [data-m3u8], [srcdoc]").forEach { el ->
            serverAttributes.forEach { attr -> normalizeUrl(pageUrl, el.attr(attr))?.let { if (looksLikeMediaOrPlayer(it) || looksLikeEmbed(it)) out.add(it) } }
            el.attr("srcdoc").takeIf { it.isNotBlank() }?.let { extractUrlsFromHtml(pageUrl, it).forEach { url -> out.add(url) } }
        }
        iframeRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.forEach { out.add(it) }
        return out.toList()
    }

    private fun extractAttributeUrls(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        document.select("iframe, embed, video, source, option, a[href], button, div, span, li").forEach { element ->
            serverAttributes.forEach { attr -> normalizeUrl(pageUrl, element.attr(attr))?.let { out.add(it) } }
        }
        return out.distinct()
    }

    private fun normalizedHtml(document: Document): String = CgvIndoUtils.decodeMaybe(document.html())
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")

    private fun normalizeUrl(pageUrl: String, value: String?): String? {
        val raw = CgvIndoUtils.decodeMaybe(value.orEmpty()).trim().trim('"', '\'', ',', ';', ' ')
        if (raw.isBlank()) return null
        val low = raw.lowercase()
        if (low == "#" || low == "null" || low == "undefined" || low == "about:blank") return null
        if (low.startsWith("javascript:") || low.startsWith("data:") || low.startsWith("blob:") || low.startsWith("intent:")) return null
        if (low.contains(".jpg") || low.contains(".png") || low.contains(".webp") || low.contains(".gif") || low.contains(".css")) return null
        if (low.contains("youtube.com") || low.contains("youtu.be") || low.contains("trailer")) return null
        if (isDeniedUrl(low)) return null
        return CgvIndoUtils.absoluteUrl(pageUrl, raw)
    }

    private fun decodeBase64Candidates(html: String): List<String> {
        val out = linkedSetOf<String>()
        val candidates = mutableListOf<String>()
        atobRegex.findAll(html).map { it.groupValues[1] }.forEach { candidates.add(it) }
        base64StringRegex.findAll(html).map { it.groupValues[1] }.take(80).forEach { candidates.add(it) }
        candidates.forEach { encoded ->
            val normalized = encoded.replace('-', '+').replace('_', '/')
            runCatching { String(Base64.decode(normalized, Base64.DEFAULT)) }.getOrNull()?.let { decoded ->
                if (decoded.contains("http") || decoded.contains("iframe") || decoded.contains("m3u8")) out.add(CgvIndoUtils.decodeMaybe(decoded))
            }
        }
        return out.toList()
    }

    private fun prioritizeCandidates(list: List<String>): List<String> = list.distinct().sortedWith(
        compareByDescending<String> { looksLikeDirectOrHls(it) }
            .thenByDescending { knownHost(it) }
            .thenBy { it.length }
    )

    private fun looksLikeDirectOrHls(url: String): Boolean = looksLikeHls(url) || looksLikeDirectMp4(url) || "videoplayback" in url.lowercase() || "googlevideo" in url.lowercase()
    private fun looksLikeHls(url: String): Boolean = url.lowercase().contains(".m3u8") || url.lowercase().contains("m3u8") || url.lowercase().contains("hls")
    private fun looksLikeDirectMp4(url: String): Boolean = url.lowercase().contains(".mp4")
    private fun shouldProbeAsHls(url: String): Boolean {
        val low = url.lowercase()
        return listOf("playlist", "master", "chunklist", "m3u", "hls", "stream").any { it in low } && !looksLikeEmbed(low)
    }

    private fun looksLikeMediaOrPlayer(url: String): Boolean = looksLikeDirectOrHls(url) || looksLikeEmbed(url) || knownHost(url)
    private fun looksLikeEmbed(url: String): Boolean {
        val low = url.lowercase()
        return listOf("/embed", "/player", "iframe", "stream", "filemoon", "dood", "streamtape", "streamwish", "vidhide", "vidguard", "short.ink", "mp4upload").any { it in low }
    }

    private fun knownHost(url: String): Boolean {
        val low = url.lowercase()
        return listOf(
            "streamtape", "dood", "doodstream", "filemoon", "vidhide", "vidguard", "streamwish", "filelions", "streamruby",
            "mp4upload", "upstream", "mixdrop", "streamsb", "sbembed", "luluvdo", "voe", "uqload", "short.ink", "hydrax", "streamvid"
        ).any { it in low }
    }

    private fun canRecurseInto(url: String, referer: String, depth: Int): Boolean {
        if (depth >= MAX_HOPS) return false
        val low = url.lowercase()
        if (looksLikeDirectOrHls(low)) return false
        if (isDeniedUrl(low)) return false
        val sameOrigin = CgvIndoUtils.originOf(url) == CgvIndoUtils.originOf(referer)
        if (sameOrigin) return listOf("player", "embed", "ajax", "wp-json", "admin-ajax", "doo_player", "dt_player").any { it in low }
        return true
    }

    private fun isDeniedUrl(url: String): Boolean {
        val low = url.lowercase()
        return listOf(
            "facebook", "twitter", "instagram", "whatsapp", "telegram", "t.me", "mailto:", "javascript:",
            "doubleclick", "googletagmanager", "google-analytics", "ads", "analytics", "wp-content/themes", "wp-content/plugins"
        ).any { it in low }
    }
}
