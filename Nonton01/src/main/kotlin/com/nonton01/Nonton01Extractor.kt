package com.nonton01

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.extractors.helper.AesHelper
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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

object Nonton01Extractor {
    private const val TAG = "Nonton01"
    private const val MAX_HOPS = 5
    private const val MAX_DIRECT_CANDIDATES = 26
    private const val MAX_EMBED_CANDIDATES = 32
    private const val MAX_AJAX_CANDIDATES = 240

    private val keyValueRegex = Regex(
        """(?i)(?:file|src|url|source|hls|hlsUrl|video|videoUrl|stream|streamUrl|playlist|embed|iframe|link|player|content)
            .{0,16}[:=]\s*['"]([^'"]+)['"]""".trimIndent().replace("\n", "")
    )
    private val quotedUrlRegex = Regex(
        """(?i)['"]((?:https?:)?//[^'"<>\s]+|/[^'"<>\s]+)['"]"""
    )
    private val bareMediaRegex = Regex(
        """(?i)(?:https?:)?//[^\s'"<>]+?(?:m3u8|mp4|webm|mkv|txt|videoplayback|get_video|master|playlist)[^\s'"<>]*"""
    )
    private val packedEvalRegex = Regex(
        """eval\s*\(\s*function\s*\(p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*(?:r|d)\s*\).*?\}\s*\(\s*['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split\s*\(\s*['"]\|['"]\s*\)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val iframeRegex = Regex("""(?i)<iframe[^>]+src\s*=\s*['"]([^'"]+)['"]""")
    private val encodedUrlRegex = Regex("""https?%3A%2F%2F[^'"<>\s]+""", RegexOption.IGNORE_CASE)
    private val atobRegex = Regex("""(?i)atob\s*\(\s*['"]([A-Za-z0-9+/=_-]{16,})['"]\s*\)""")
    private val base64StringRegex = Regex("""['"]([A-Za-z0-9+/=]{28,})['"]""")
    private val dataPostRegex = Regex("""(?i)data-(?:post|id|movie|movieid)\s*=\s*['"]?(\d+)""")
    private val dataNumeRegex = Regex("""(?i)data-(?:nume|server|episode)\s*=\s*['"]?(\d+)""")
    private val dataTypeRegex = Regex("""(?i)data-type\s*=\s*['"]?([a-zA-Z0-9_-]+)""")
    private val jsPostRegex = Regex("""(?i)(?:post|post_id|movie_id|id)\s*[:=]\s*['"]?(\d+)""")
    private val bodyPostRegex = Regex("""(?i)(?:postid-|post-|wp-post-)(\d{2,})""")
    private val ajaxUrlRegex = Regex("""(?i)(?:ajaxurl|ajax_url|admin_ajax|adminajax|ajaxUrl)\s*[=:]\s*[\'"]([^\'"]*admin-ajax\.php[^\'"]*)[\'"]""")
    private val anyAdminAjaxRegex = Regex("""(?i)(?:https?:)?//[^\'"<>\s]+admin-ajax\.php[^\'"<>\s]*|/[^\'"<>\s]*admin-ajax\.php[^\'"<>\s]*""")
    private val nonceRegex = Regex("""(?i)(?:nonce|security|_wpnonce|player_nonce|doo_nonce|dtNonce|playnonce)\s*[=:]\s*[\'"]([A-Za-z0-9_\-]{5,})[\'"]""")

    private data class AjaxProbe(
        val post: String,
        val nume: String,
        val type: String,
        val source: String
    )

    private val serverAttributes = listOf(
        "src", "href", "value", "data-src", "data-url", "data-link", "data-href",
        "data-file", "data-video", "data-video-url", "data-stream", "data-stream-url",
        "data-embed", "data-iframe", "data-player", "data-play", "data-server",
        "data-content", "data-id", "data-post", "data-nume", "data-lazy-src", "srcdoc",
        "data-code", "data-hls", "data-m3u8", "data-html", "data-frame", "data-target"
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
        val allCandidates = prioritizeCandidates(
            extractAttributeUrls(pageUrl, document) +
                extractUrlsFromText(pageUrl, normalizedHtml(document)) +
                ajaxCandidates
        )
        val direct = allCandidates.filter { isDirectMediaCandidate(it) }.distinct()
        val embeds = allCandidates
            .filter { !isDirectMediaCandidate(it) && shouldProbeAsEmbed(it, pageUrl) }
            .distinct()
        Log.e(TAG, "captured page=$pageUrl depth=$depth ajax=${ajaxCandidates.size} all=${allCandidates.size} direct=${direct.size} embeds=${embeds.size} sample=${embeds.take(8)}")

        for (url in direct.take(MAX_DIRECT_CANDIDATES)) {
            Log.e(TAG, "direct candidate: $url")
            val emittedNow = emitDirect(providerName, url, pageUrl, emitted, callback)
            found = found || emittedNow
            if (!emittedNow && depth < MAX_HOPS && canRecurseInto(url, pageUrl)) {
                val nestedFound = resolvePage(providerName, url, pageUrl, mainUrl, depth + 1, emitted, subtitleCallback, callback)
                found = found || nestedFound
            }
        }

        for (embed in embeds.take(MAX_EMBED_CANDIDATES)) {
            Log.e(TAG, "embed candidate: $embed referer=$pageUrl")
            val customFound = runCustomHostExtractor(providerName, embed, pageUrl, emitted, subtitleCallback, callback)
            val extractorFound = customFound || runExtractor(embed, pageUrl, emitted, subtitleCallback, callback)
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
        val playableUrl = normalizePlayableUrl(url)
        return try {
            when {
                looksLikeHls(playableUrl) -> {
                    val links = generateM3u8(
                        source = providerName,
                        streamUrl = playableUrl,
                        referer = referer,
                        headers = videoHeaders(referer)
                    )
                    links.forEach { link ->
                        if (emitted.add(link.url)) callback(link)
                    }
                    if (links.isEmpty()) Log.e(TAG, "generateM3u8 returned empty: $playableUrl")
                    links.isNotEmpty()
                }
                looksLikeDirectMp4(playableUrl) -> {
                    if (emitted.add(playableUrl)) {
                        callback(
                            newExtractorLink(providerName, "$providerName MP4", playableUrl) {
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
                    if (!shouldTryHlsFallback(playableUrl)) return false
                    val links = runCatching {
                        generateM3u8(
                            source = providerName,
                            streamUrl = playableUrl,
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


    private suspend fun runCustomHostExtractor(
        providerName: String,
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val low = url.lowercase()
        return when {
            low.contains("dood.") || low.contains("doodstream") || low.contains("doodwatch") ->
                extractDood(providerName, url, referer, emitted, callback)
            low.contains("streamtape") || low.contains("stape") ->
                extractStreamTape(providerName, url, referer, emitted, callback)
            low.contains("jeniusplay") ->
                extractJeniusplay(providerName, url, referer, emitted, subtitleCallback, callback)
            low.contains("filemoon") || low.contains("vidhide") || low.contains("vidguard") ||
                low.contains("streamwish") || low.contains("filelions") || low.contains("streamruby") ||
                low.contains("playcinematic") || low.contains("player4u") || low.contains("hglink") ||
                low.contains("myvidplay") || low.contains("minochinos") ->
                extractPackedPlayer(providerName, url, referer, emitted, subtitleCallback, callback)
            else -> false
        }
    }

    private suspend fun extractJeniusplay(
        providerName: String,
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = originOf(url) ?: "https://jeniusplay.com"
        val fixedUrl = normalizeUrl(referer, url) ?: url
        val html = runCatching { app.get(fixedUrl, headers = Nonton01Utils.siteHeadersFor(referer), referer = referer).text }
            .onFailure { Log.e(TAG, "jeniusplay GET failed $fixedUrl: ${it.message}") }
            .getOrNull()

        val hash = fixedUrl.substringAfter("data=", "")
            .substringBefore("&")
            .ifBlank { fixedUrl.trimEnd('/').substringAfterLast('/') }
            .trim()

        var found = false
        if (hash.isNotBlank()) {
            val response = runCatching {
                app.post(
                    url = "${origin.trimEnd('/')}/player/index.php?data=$hash&do=getVideo",
                    data = mapOf("hash" to hash, "r" to referer),
                    referer = referer,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json, text/javascript, */*; q=0.01"
                    )
                ).text
            }.onFailure { Log.e(TAG, "jeniusplay ajax failed hash=$hash: ${it.message}") }.getOrNull()

            val source = response
                ?.let { normalizedText(it) }
                ?.let { raw ->
                    runCatching { JSONObject(raw).optString("videoSource") }.getOrNull()
                        ?.ifBlank { runCatching { JSONObject(raw).optString("securedLink") }.getOrNull().orEmpty() }
                        ?.ifBlank { extractUrlsFromText(fixedUrl, raw).firstOrNull().orEmpty() }
                }
                ?.takeIf { it.isNotBlank() }
                ?.let { normalizeUrl(fixedUrl, it) }

            if (!source.isNullOrBlank()) {
                found = emitDirect(providerName, source, fixedUrl, emitted, callback) || found
            }
        }

        if (!html.isNullOrBlank()) {
            collectSubtitles(fixedUrl, Jsoup.parse(html, fixedUrl), subtitleCallback)
            prioritizeCandidates(extractUrlsFromText(fixedUrl, html)).take(16).forEach { candidate ->
                found = emitDirect(providerName, candidate, fixedUrl, emitted, callback) || found
                if (!found && shouldProbeAsEmbed(candidate, fixedUrl)) {
                    found = runExtractor(candidate, fixedUrl, emitted, subtitleCallback, callback) || found
                }
            }
        }

        Log.e(TAG, "custom jeniusplay found=$found url=$fixedUrl")
        return found
    }

    private suspend fun extractPackedPlayer(
        providerName: String,
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching { app.get(url, headers = Nonton01Utils.siteHeadersFor(referer), referer = referer) }
            .onFailure { Log.e(TAG, "custom packed GET failed $url: ${it.message}") }
            .getOrNull() ?: return false
        val html = normalizedText(response.text)
        val candidates = prioritizeCandidates(extractUrlsFromText(url, html))
        var found = false
        for (candidate in candidates.take(20)) {
            found = emitDirect(providerName, candidate, url, emitted, callback) || found
            if (!found && shouldProbeAsEmbed(candidate, url)) {
                found = runExtractor(candidate, url, emitted, subtitleCallback, callback) || found
            }
        }
        Log.e(TAG, "custom packed host result found=$found url=$url candidates=${candidates.take(8)}")
        return found
    }

    private suspend fun extractStreamTape(
        providerName: String,
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching { app.get(url, headers = Nonton01Utils.siteHeadersFor(referer), referer = referer).text }
            .onFailure { Log.e(TAG, "streamtape GET failed $url: ${it.message}") }
            .getOrNull() ?: return false
        val normalized = normalizedText(html)
        val direct = linkedSetOf<String>()
        Regex("""(?i)(?:https?:)?//[^'"<>\s]+?/get_video\?[^'"<>\s]+""")
            .findAll(normalized)
            .mapNotNull { normalizeUrl(url, it.value) }
            .forEach { direct.add(it) }
        Regex("""(?i)robotlink['"]?\)?\.innerHTML\s*=\s*['"]([^'"]+)['"]\s*\+\s*\(?\s*['"]([^'"]+)['"]""")
            .find(normalized)
            ?.let { match -> normalizeUrl(url, match.groupValues[1] + match.groupValues[2]) }
            ?.let { direct.add(it) }
        var found = false
        direct.forEach { finalUrl ->
            if (emitted.add(finalUrl)) {
                callback(
                    newExtractorLink(providerName, "$providerName StreamTape", finalUrl) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        this.headers = videoHeaders(url)
                    }
                )
                found = true
            }
        }
        Log.e(TAG, "custom streamtape found=$found url=$url candidates=${direct.take(4)}")
        return found
    }

    private suspend fun extractDood(
        providerName: String,
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = originOf(url) ?: return false
        val html = runCatching { app.get(url, headers = Nonton01Utils.siteHeadersFor(referer), referer = referer).text }
            .onFailure { Log.e(TAG, "dood GET failed $url: ${it.message}") }
            .getOrNull() ?: return false
        val passPath = Regex("""(/pass_md5/[^'"<>\s]+)""").find(html)?.groupValues?.getOrNull(1) ?: return false
        val token = Regex("""token=([^&'"<>\s]+)""").find(passPath)?.groupValues?.getOrNull(1).orEmpty()
        val seed = runCatching { app.get(origin.trimEnd('/') + passPath, headers = Nonton01Utils.videoHeaders(url), referer = url).text }
            .onFailure { Log.e(TAG, "dood pass_md5 failed: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return false
        val random = (1..10).joinToString("") { ('a'..'z').random().toString() }
        val finalUrl = seed + random + if (token.isNotBlank()) "?token=$token&expiry=${System.currentTimeMillis()}" else ""
        if (emitted.add(finalUrl)) {
            callback(
                newExtractorLink(providerName, "$providerName Dood", finalUrl) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = videoHeaders(url)
                }
            )
            Log.e(TAG, "custom dood emitted: $finalUrl")
            return true
        }
        return false
    }

    private suspend fun fetchAjaxPlayers(pageUrl: String, mainUrl: String, document: Document): List<String> {
        val html = normalizedHtml(document)
        val origin = originOf(pageUrl) ?: originOf(mainUrl) ?: Nonton01Seeds.MAIN_URL
        val posts = linkedSetOf<String>()
        val numes = linkedSetOf<String>()
        val types = linkedSetOf<String>()
        val exactProbes = linkedSetOf<AjaxProbe>()

        val optionSelectors = listOf(
            ".dooplay_player_option",
            "#playeroptionsul li",
            "ul#playeroptionsul li",
            ".player_sorces li",
            ".player option",
            ".player-option",
            ".server",
            ".server-item",
            "[data-post]",
            "[data-id]",
            "[data-nume]",
            "[data-type]"
        ).joinToString(", ")

        document.select(optionSelectors).forEach { element ->
            val postValues = listOf("data-post", "data-id", "data-movie", "data-movieid", "data-postid", "post", "id")
                .map { element.attr(it).trim() }
                .filter { it.isNotBlank() && it.all { ch -> ch.isDigit() } }
            val numeValues = listOf("data-nume", "data-server", "data-episode", "data-ep", "nume", "server", "episode")
                .map { element.attr(it).trim() }
                .filter { it.isNotBlank() && it.all { ch -> ch.isDigit() } }
            val typeValues = listOf("data-type", "type")
                .map { element.attr(it).trim() }
                .filter { it.isNotBlank() }
                .map { normalizeAjaxType(it) }

            postValues.forEach { posts.add(it) }
            numeValues.forEach { numes.add(it) }
            typeValues.forEach { types.add(it) }

            val identityText = listOf(
                element.id(),
                element.className(),
                element.attr("onclick"),
                element.attr("data-target"),
                element.attr("data-options"),
                element.attr("data-json"),
                element.text()
            ).joinToString(" ")
            extractAjaxIdsFromText(identityText, posts, numes, types)

            val elementPostValues = postValues.ifEmpty { emptyList() }
            val elementNumeValues = numeValues.ifEmpty {
                val single = Regex("""(?i)(?:player[-_]?option|server|option)[-_]?(\d{1,2})""")
                    .find(identityText)
                    ?.groupValues
                    ?.getOrNull(1)
                single?.let { listOf(it) }.orEmpty()
            }
            val elementTypeValues = typeValues.ifEmpty { inferAjaxTypes(pageUrl) }
            elementPostValues.take(2).forEach { post ->
                elementNumeValues.take(4).forEach { nume ->
                    elementTypeValues.take(3).forEach { type ->
                        exactProbes.add(AjaxProbe(post, nume, normalizeAjaxType(type), "element"))
                    }
                }
            }
        }

        dataPostRegex.findAll(html).map { it.groupValues[1] }.forEach { posts.add(it) }
        dataNumeRegex.findAll(html).map { it.groupValues[1] }.forEach { numes.add(it) }
        dataTypeRegex.findAll(html).map { it.groupValues[1] }.forEach { types.add(normalizeAjaxType(it)) }
        jsPostRegex.findAll(html).map { it.groupValues[1] }.take(6).forEach { posts.add(it) }
        bodyPostRegex.findAll(html).map { it.groupValues[1] }.take(8).forEach { posts.add(it) }
        extractAjaxIdsFromText(html, posts, numes, types)

        if (posts.isEmpty()) {
            Log.e(TAG, "ajax attributes missing: no post/id found for $pageUrl")
            return emptyList()
        }
        if (numes.isEmpty()) (1..10).map { it.toString() }.forEach { numes.add(it) }

        val inferredTypes = inferAjaxTypes(pageUrl)
        val orderedTypes = linkedSetOf<String>()
        if (types.isNotEmpty()) {
            types.map { normalizeAjaxType(it) }.forEach { orderedTypes.add(it) }
        } else {
            inferredTypes.forEach { orderedTypes.add(it) }
        }
        // Broad fallbacks are deliberately appended after the inferred/extracted type.
        listOf("movie", "tv", "episode", "movies", "film", "serie").forEach { orderedTypes.add(it) }

        val probes = linkedSetOf<AjaxProbe>()
        exactProbes.forEach { probes.add(it) }
        posts.take(5).forEach { post ->
            numes.take(10).forEach { nume ->
                orderedTypes.take(6).forEach { type -> probes.add(AjaxProbe(post, nume, normalizeAjaxType(type), "fallback")) }
            }
        }

        Log.e(TAG, "ajax attrs page=$pageUrl posts=${posts.take(8)} numes=${numes.take(12)} types=${orderedTypes.take(8)} exact=${exactProbes.size}")

        val endpoints = extractAjaxEndpoints(pageUrl, origin, document, html)
        val actions = listOf(
            "doo_player_ajax",
            "dt_player_ajax",
            "player_ajax",
            "player_ajax_get",
            "get_player",
            "get_video",
            "get_embed",
            "getEmbed",
            "get_player_content"
        )
        val nonceValues = extractAjaxNonces(document, html)

        val out = linkedSetOf<String>()
        var attempts = 0
        for (probe in probes) {
            for (endpoint in endpoints) {
                for (action in actions) {
                    if (attempts >= MAX_AJAX_CANDIDATES) {
                        Log.e(TAG, "ajax probe limit reached attempts=$attempts candidates=${out.size}")
                        return prioritizeCandidates(out.toList())
                    }
                    attempts++
                    val forms = buildAjaxForms(action, probe, nonceValues)
                    val ajaxHeaders = Nonton01Utils.siteHeadersFor(pageUrl) + mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Referer" to pageUrl,
                        "Origin" to origin,
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                    Log.e(TAG, "ajax probe#$attempts endpoint=$endpoint action=$action post=${probe.post} nume=${probe.nume} type=${probe.type} source=${probe.source} nonce=${nonceValues.keys}")

                    var responseText: String? = null
                    for (form in forms) {
                        responseText = runCatching {
                            val body = form.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull())
                            val res = app.post(endpoint, requestBody = body, headers = ajaxHeaders)
                            val text = res.text
                            Log.e(TAG, "ajax POST status=${res.code} action=$action post=${probe.post} nume=${probe.nume} type=${probe.type} body=${snippet(text)}")
                            text
                        }.onFailure { Log.e(TAG, "ajax POST failed endpoint=$endpoint action=$action post=${probe.post} nume=${probe.nume} type=${probe.type} error=${it.message}") }
                            .getOrNull()
                        if (!responseText.isNullOrBlank() && responseText.trim() != "0") break
                    }

                    responseText = responseText ?: runCatching {
                        val form = forms.firstOrNull().orEmpty()
                        val res = app.get("$endpoint?$form", headers = ajaxHeaders)
                        val text = res.text
                        Log.e(TAG, "ajax GET status=${res.code} action=$action post=${probe.post} nume=${probe.nume} type=${probe.type} body=${snippet(text)}")
                        text
                    }.onFailure { Log.e(TAG, "ajax GET failed endpoint=$endpoint action=$action post=${probe.post} nume=${probe.nume} type=${probe.type} error=${it.message}") }
                        .getOrNull()

                    if (responseText.isNullOrBlank()) {
                        Log.e(TAG, "ajax empty response endpoint=$endpoint action=$action post=${probe.post} nume=${probe.nume} type=${probe.type}")
                        continue
                    }
                    if (responseText.trim() == "0") {
                        Log.e(TAG, "ajax response zero endpoint=$endpoint action=$action post=${probe.post} nume=${probe.nume} type=${probe.type}")
                        continue
                    }

                    val payloadText = ajaxPayloadText(responseText)
                    val urls = extractUrlsFromText(pageUrl, payloadText)
                    if (urls.isEmpty()) {
                        Log.e(TAG, "ajax parsed 0 urls action=$action post=${probe.post} nume=${probe.nume} type=${probe.type} payload=${snippet(payloadText)}")
                    } else {
                        Log.e(TAG, "ajax captured ${urls.size} urls action=$action post=${probe.post} nume=${probe.nume} type=${probe.type} urls=${urls.take(6)}")
                        urls.forEach { out.add(it) }
                    }
                }
            }
        }
        Log.e(TAG, "ajax probes done attempts=$attempts candidates=${out.size}")
        return prioritizeCandidates(out.toList())
    }

    private fun extractAjaxEndpoints(pageUrl: String, origin: String, document: Document, html: String): List<String> {
        val endpoints = linkedSetOf<String>()
        ajaxUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.forEach { endpoints.add(it) }
        anyAdminAjaxRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.value) }.forEach { endpoints.add(it) }
        document.select("form[action*='admin-ajax.php'], [data-ajaxurl], [data-ajax-url]").forEach { element ->
            listOf("action", "data-ajaxurl", "data-ajax-url")
                .map { element.attr(it) }
                .mapNotNull { normalizeUrl(pageUrl, it) }
                .forEach { endpoints.add(it) }
        }
        listOf(
            "$origin/wp-admin/admin-ajax.php",
            "$origin/admin-ajax.php",
            "$origin/wp-json/dooplay/v1/player"
        ).forEach { endpoints.add(it) }
        return endpoints.distinct()
    }

    private fun extractAjaxNonces(document: Document, html: String): Map<String, String> {
        val out = linkedMapOf<String, String>()

        Regex("""(?i)window\.idlixNonce\s*=\s*['"]([A-Za-z0-9_\-]+)['"]""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { out["_n"] = it }
        Regex("""(?i)window\.idlixTime\s*=\s*['"]?(\d{5,})['"]?""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { out["_t"] = it }

        nonceRegex.findAll(html).forEach { match ->
            val whole = match.value.lowercase()
            val value = match.groupValues[1]
            val key = when {
                whole.contains("security") -> "security"
                whole.contains("wpnonce") -> "_wpnonce"
                whole.contains("player_nonce") -> "player_nonce"
                whole.contains("doo_nonce") -> "doo_nonce"
                whole.contains("nonce") -> "nonce"
                else -> "nonce"
            }
            if (value.isNotBlank()) out[key] = value
        }

        Regex("""(?i)(?:nonce|security|_wpnonce|player_nonce|doo_nonce|dtNonce|playnonce)['"]?\s*:\s*['"]([A-Za-z0-9_\-]{5,})['"]""")
            .findAll(html)
            .forEach { match ->
                val whole = match.value.lowercase()
                val key = when {
                    whole.contains("security") -> "security"
                    whole.contains("wpnonce") -> "_wpnonce"
                    whole.contains("player_nonce") -> "player_nonce"
                    whole.contains("doo_nonce") -> "doo_nonce"
                    whole.contains("nonce") -> "nonce"
                    else -> "nonce"
                }
                out[key] = match.groupValues[1]
            }

        document.select("input[name*=nonce], input[name=security], input[name=_wpnonce], [data-nonce], [data-security]").forEach { element ->
            val name = element.attr("name").ifBlank {
                when {
                    element.hasAttr("data-security") -> "security"
                    else -> "nonce"
                }
            }
            val value = element.attr("value").ifBlank { element.attr("data-nonce") }.ifBlank { element.attr("data-security") }
            if (name.isNotBlank() && value.isNotBlank()) out[name] = value
        }
        return out.filterValues { it.length >= 5 }
    }

    private fun buildAjaxForms(action: String, probe: AjaxProbe, nonces: Map<String, String>): List<String> {
        val base = linkedMapOf(
            "action" to action,
            "post" to probe.post,
            "nume" to probe.nume,
            "type" to probe.type
        )
        val playerTokens = nonces.toMutableMap().apply {
            if (containsKey("_n")) put("_p", probe.post)
        }

        val variants = mutableListOf<Map<String, String>>()
        variants.add(base + playerTokens)
        variants.add(base + nonces)
        variants.add(base + mapOf("id" to probe.post) + playerTokens)
        variants.add(base + mapOf("post_id" to probe.post, "server" to probe.nume) + nonces)
        variants.add(base + mapOf("movie_id" to probe.post, "episode" to probe.nume) + nonces)
        variants.add(mapOf("action" to action, "id" to probe.post, "server" to probe.nume, "type" to probe.type) + playerTokens)
        variants.add(mapOf("action" to action, "post_id" to probe.post, "nume" to probe.nume, "type" to probe.type) + nonces)

        return variants.distinct().map { params ->
            params.entries.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun ajaxPayloadText(raw: String): String {
        val normalized = normalizedText(raw)
        val collected = linkedSetOf<String>()

        fun collectJsonValue(value: Any?) {
            when (value) {
                null -> Unit
                JSONObject.NULL -> Unit
                is JSONObject -> {
                    extractEncryptedDooPlay(value).forEach { collected.add(it) }
                    value.keys().forEach { key -> collectJsonValue(value.opt(key)) }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) collectJsonValue(value.opt(i))
                }
                is String -> {
                    val v = normalizedText(value)
                    if (v.isNotBlank() && v != "0" && !v.equals("null", ignoreCase = true)) collected.add(v)
                    if ((v.startsWith("{") && v.endsWith("}")) || (v.startsWith("[") && v.endsWith("]"))) {
                        runCatching { collectJsonValue(JSONObject(v)) }
                        runCatching { collectJsonValue(JSONArray(v)) }
                    }
                }
                else -> collectJsonValue(value.toString())
            }
        }

        runCatching { collectJsonValue(JSONObject(normalized)) }
        runCatching { collectJsonValue(JSONArray(normalized)) }

        if (collected.isEmpty()) collected.add(normalized)
        return collected.joinToString("\n")
    }

    private fun extractEncryptedDooPlay(json: JSONObject): List<String> {
        val out = linkedSetOf<String>()
        val embed = listOf("embed_url", "embed", "url", "link", "source", "file", "src")
            .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() && !it.equals("null", true) } }
            ?.let { normalizedText(it) }
            ?: return emptyList()

        if (embed.startsWith("http", true) || embed.startsWith("//") || embed.contains("<iframe", true)) {
            out.add(embed)
        }

        val key = json.optString("key").ifBlank { json.optString("hash") }
        if (key.isNotBlank()) {
            val metric = runCatching { JSONObject(embed).optString("m") }.getOrNull().orEmpty()
            if (metric.isNotBlank()) {
                buildDooPlayKeys(key, metric).forEach { password ->
                    val decoded = runCatching {
                        AesHelper.cryptoAESHandler(embed, password.toByteArray(), false)
                    }.getOrNull()?.let { normalizedText(it).fixBloat() }
                    if (!decoded.isNullOrBlank() && !decoded.equals("null", true)) out.add(decoded)
                }
            }
        }
        return out.toList()
    }

    private fun buildDooPlayKeys(rawKey: String, metric: String): List<String> {
        val decodedMetric = safeBase64Decode(metric.reversed()) ?: return emptyList()
        val indexes = decodedMetric.split('|').mapNotNull { it.toIntOrNull() }
        val rawParts = rawKey.split("\\x")
        val filteredParts = rawParts.filter { it.isNotBlank() }
        val keys = linkedSetOf<String>()

        fun build(parts: List<String>, offset: Int) {
            val key = indexes.joinToString("") { index ->
                parts.getOrNull(index + offset)?.let { "\\x$it" }.orEmpty()
            }
            if (key.isNotBlank()) keys.add(key)
        }

        build(filteredParts, 0)
        build(filteredParts, 1)
        build(rawParts, 0)
        build(rawParts, 1)
        return keys.toList()
    }

    private fun safeBase64Decode(value: String): String? {
        return runCatching {
            val normalized = value.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(Base64.decode(padded, Base64.DEFAULT))
        }.getOrNull()
    }

    private fun String.fixBloat(): String {
        return replace("\\/", "/").replace("\\\"", "\"").replace("\\", "").trim('"', '\'', ' ')
    }

    private fun extractAjaxIdsFromText(
        text: String,
        posts: MutableSet<String>,
        numes: MutableSet<String>,
        types: MutableSet<String>
    ) {
        Regex("""(?i)(?:player[-_]?option|player_option|option|server)[-_](\d{2,})[-_](\d{1,2})""")
            .findAll(text)
            .forEach {
                posts.add(it.groupValues[1])
                numes.add(it.groupValues[2])
            }
        Regex("""(?i)(?:data|post|movie)[-_](\d{2,})[-_](\d{1,2})[-_]([a-z]+)""")
            .findAll(text)
            .forEach {
                posts.add(it.groupValues[1])
                numes.add(it.groupValues[2])
                types.add(normalizeAjaxType(it.groupValues[3]))
            }
        Regex("""(?i)(?:nume|server|episode)[-_=: ]+(\d{1,2})""")
            .findAll(text)
            .map { it.groupValues[1] }
            .forEach { numes.add(it) }
        Regex("""(?i)(?:type)[-_=: ]+([a-zA-Z0-9_-]+)""")
            .findAll(text)
            .map { it.groupValues[1] }
            .forEach { types.add(normalizeAjaxType(it)) }
    }

    private fun normalizeAjaxType(value: String): String {
        val v = value.trim().lowercase()
        return when {
            v.contains("episode") || v == "ep" -> "episode"
            v.contains("tv") || v.contains("series") -> "tv"
            v.contains("movie") || v.contains("film") -> "movie"
            v.isBlank() -> "movie"
            else -> v
        }
    }

    private fun inferAjaxTypes(pageUrl: String): List<String> {
        val low = pageUrl.lowercase()
        return when {
            low.contains("/episodes/") || low.contains("/episode/") -> listOf("episode", "tv")
            low.contains("/tvshows/") || low.contains("/series/") -> listOf("tv", "episode")
            low.contains("/movies/") || low.contains("/movie/") -> listOf("movie")
            else -> listOf("movie")
        }
    }

    private fun snippet(text: String?, max: Int = 300): String {
        return text.orEmpty()
            .replace("\n", " ")
            .replace("\r", " ")
            .replace(Regex("\\s+"), " ")
            .take(max)
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

        if (html.contains("<", ignoreCase = true) &&
            (html.contains("iframe", ignoreCase = true) ||
                html.contains("video", ignoreCase = true) ||
                html.contains("source", ignoreCase = true) ||
                html.contains("data-url", ignoreCase = true) ||
                html.contains("data-src", ignoreCase = true) ||
                html.contains("data-file", ignoreCase = true) ||
                html.contains("data-embed", ignoreCase = true))) {
            runCatching {
                val miniDoc = Jsoup.parseBodyFragment(html, pageUrl)
                miniDoc.select("iframe[src], embed[src], video[src], source[src], a[href], [data-src], [data-url], [data-file], [data-embed], [data-iframe], [data-hls], [data-m3u8], [data-html], [srcdoc]").forEach { element ->
                    listOf("src", "href", "data-src", "data-url", "data-file", "data-embed", "data-iframe", "data-hls", "data-m3u8", "data-html", "srcdoc")
                        .map { element.attr(it) }
                        .flatMap { attr -> if (attr.contains("<") || attr.contains("http", true) || attr.contains("\\/")) extractUrlsFromText(pageUrl, attr) else listOfNotNull(normalizeUrl(pageUrl, attr)) }
                        .forEach { out.add(it) }
                }
            }.onFailure { Log.e(TAG, "mini HTML parse failed: ${it.message}") }
        }

        keyValueRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.forEach { out.add(it) }
        quotedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.forEach { out.add(it) }
        iframeRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.forEach { out.add(it) }
        bareMediaRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.value) }.forEach { out.add(it) }
        encodedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.value) }.forEach { out.add(it) }
        decodeBase64Candidates(html).mapNotNull { normalizeUrl(pageUrl, it) }.forEach { out.add(it) }
        decodePackedScripts(html).forEach { unpacked ->
            keyValueRegex.findAll(unpacked).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.forEach { out.add(it) }
            quotedUrlRegex.findAll(unpacked).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.forEach { out.add(it) }
            iframeRegex.findAll(unpacked).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.forEach { out.add(it) }
            bareMediaRegex.findAll(unpacked).mapNotNull { normalizeUrl(pageUrl, it.value) }.forEach { out.add(it) }
            encodedUrlRegex.findAll(unpacked).mapNotNull { normalizeUrl(pageUrl, it.value) }.forEach { out.add(it) }
            decodeBase64Candidates(unpacked).mapNotNull { normalizeUrl(pageUrl, it) }.forEach { out.add(it) }
        }
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

    private fun normalizedText(text: String): String {
        var output = text
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
        output = Regex("""\\x([0-9a-fA-F]{2})""").replace(output) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return output
    }

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


    private fun decodePackedScripts(html: String): List<String> {
        return packedEvalRegex.findAll(html)
            .mapNotNull { match ->
                val payload = normalizedText(match.groupValues[1])
                val radix = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val count = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                val symbols = normalizedText(match.groupValues[4]).split('|')
                unpackPacker(payload, radix, count, symbols)
            }
            .filter { it.contains("http", ignoreCase = true) || it.contains("m3u8", ignoreCase = true) || it.contains("iframe", ignoreCase = true) }
            .distinct()
            .toList()
    }

    private fun unpackPacker(payload: String, radix: Int, count: Int, symbols: List<String>): String? {
        return runCatching {
            var source = payload
            for (i in count - 1 downTo 0) {
                val replacement = symbols.getOrNull(i).orEmpty()
                if (replacement.isBlank()) continue
                val token = encodePackerIndex(i, radix)
                source = Regex("""\b${Regex.escape(token)}\b""").replace(source, replacement)
            }
            normalizedText(source)
        }.onFailure { Log.e(TAG, "packer decode failed: ${it.message}") }.getOrNull()
    }

    private fun encodePackerIndex(value: Int, radix: Int): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (value < radix) return alphabet.getOrElse(value) { value.toString().first() }.toString()
        return encodePackerIndex(value / radix, radix) + alphabet.getOrElse(value % radix) { '0' }
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

    private fun normalizePlayableUrl(url: String): String {
        val low = url.lowercase()
        return when {
            low.contains(".txt") && (low.contains("hls") || low.contains("m3u") || low.contains("master") || low.contains("playlist") || low.contains("playcinematic")) ->
                url.replace(Regex("""\.txt(?=\?|$)""", RegexOption.IGNORE_CASE), ".m3u8")
            else -> url
        }
    }

    private fun looksLikeHls(url: String): Boolean {
        val low = url.lowercase()
        return low.contains(".m3u8") || low.contains("m3u8") || low.contains("playlist") || low.contains("master.m3u") || (low.contains(".txt") && low.contains("hls"))
    }

    private fun looksLikeDirectMp4(url: String): Boolean {
        val low = url.lowercase()
        return low.contains(".mp4") || low.contains(".webm") || low.contains(".mkv") || low.contains("googlevideo") || low.contains("videoplayback") || low.contains("/get_video?")
    }

    private fun isDirectMediaCandidate(url: String): Boolean {
        val low = url.lowercase()
        return !isDeniedUrl(low) &&
            (looksLikeHls(url) || looksLikeDirectMp4(url) || shouldTryHlsFallback(url))
    }

    private fun looksLikeMediaOrPlayer(url: String): Boolean {
        val low = url.lowercase()
        return !isDeniedUrl(low) &&
            (isDirectMediaCandidate(url) || isKnownExtractorHost(low) || looksLikeEmbed(url))
    }

    private fun shouldProbeAsEmbed(url: String, pageUrl: String): Boolean {
        val low = url.lowercase()
        if (isDeniedUrl(low) || looksLikeDirectMp4(url) || looksLikeHls(url)) return false
        if (!low.startsWith("http://") && !low.startsWith("https://")) return false
        if (low == pageUrl.lowercase()) return false
        val pageOrigin = originOf(pageUrl)
        val urlOrigin = originOf(url)
        val sameOrigin = pageOrigin != null && pageOrigin == urlOrigin

        // Dooplay servers often return unknown iframe hosts. Do not require
        // a known extractor domain here; probe external HTML player pages too.
        return looksLikeEmbed(url) ||
            isKnownExtractorHost(low) ||
            !sameOrigin ||
            looksLikeInternalPlayerUrl(low) ||
            low.contains("player") ||
            low.contains("embed") ||
            low.contains("stream") ||
            low.contains("server") ||
            low.contains("source")
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
            "luluvdo", "vidmoly", "wolfstream", "upstream", "streamruby", "mcloud",
            "streamhide", "streamvid", "embedsito", "filegram", "dropload", "hydrax",
            "fastream", "streamhub", "vidsrc", "short.ink", "gofile", "mp4upload", "streamwish", "vembed", "vidplay", "vidcloud", "filejoker", "streamhls", "playercdn", "playhydrax", "jeniusplay", "playcinematic", "player4u", "hownetwork", "emturbovid", "majorplay", "f16"
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
        if (embedOrigin == null || isDeniedUrl(low)) return false
        if (!low.startsWith("http://") && !low.startsWith("https://")) return false
        if (low == pageUrl.lowercase()) return false
        val sameOrigin = embedOrigin == pageOrigin
        val internalPlayer = looksLikeInternalPlayerUrl(low)

        // Same-site recursion is limited to explicit player/ajax pages to avoid
        // crawling catalog/detail pages forever. External iframe hosts are allowed
        // even when Cloudstream has no extractor, because many final streams are
        // hidden one HTML layer deeper.
        return if (sameOrigin || Nonton01Utils.isSameHost(embed)) {
            internalPlayer
        } else {
            true
        }
    }

    private fun looksLikeInternalPlayerUrl(low: String): Boolean {
        return low.contains("admin-ajax.php") ||
            low.contains("doo_player") ||
            low.contains("player_ajax") ||
            low.contains("/player/") ||
            low.contains("/embed/") ||
            low.contains("/ajax/") ||
            low.contains("/wp-json/") ||
            low.contains("?player=") ||
            low.contains("?embed=") ||
            low.contains("?source=") ||
            low.contains("?url=")
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
