package com.cgvindo

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.HlsPlaylistParser
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import org.jsoup.nodes.Document

object CgvIndoExtractor {
    private const val TAG = "CgvIndo"
    private const val MAX_HOPS = 5
    private const val MAX_DIRECT = 24
    private const val MAX_EMBED = 32
    private const val MAX_AJAX_PROBES = 96

    private val keyValueRegex = Regex(
        """(?i)(?:file|src|url|source|hls|hlsUrl|video|videoUrl|stream|streamUrl|playlist|embed|iframe|link|fileUrl)\s*[:=]\s*['\"]([^'\"]+)['\"]"""
    )
    private val quotedUrlRegex = Regex("""(?i)['\"]((?:https?:)?//[^'\"<>\s]+|/[^'\"<>\s]+)['\"]""")
    private val bareMediaRegex = Regex(
        """(?i)(?:https?:)?//[^\s'\"<>]+?(?:m3u8|mp4|videoplayback|get_video|master|playlist)[^\s'\"<>]*"""
    )
    private val packedEvalRegex = Regex(
        """eval\s*\(\s*function\s*\(p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*(?:r|d)\s*\).*?\}\s*\(\s*['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split\s*\(\s*['"]\|['"]\s*\)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val iframeRegex = Regex("""(?i)<iframe[^>]+src\s*=\s*['\"]([^'\"]+)['\"]""")
    private val encodedUrlRegex = Regex("""https?%3A%2F%2F[^'\"<>\s]+""", RegexOption.IGNORE_CASE)
    private val atobRegex = Regex("""(?i)atob\s*\(\s*['\"]([A-Za-z0-9+/=_-]{16,})['\"]\s*\)""")
    private val base64StringRegex = Regex("""['\"]([A-Za-z0-9+/=]{28,})['\"]""")

    private val dataPostRegex = Regex("""(?i)data-(?:post|id|movie|movieid|postid|movie_id|post_id)\s*=\s*['\"]?(\d+)""")
    private val dataNumeRegex = Regex("""(?i)data-(?:nume|server|episode|serverid)\s*=\s*['\"]?(\d+)""")
    private val dataTypeRegex = Regex("""(?i)data-(?:type|player|kind)\s*=\s*['\"]?([a-zA-Z0-9_-]+)""")
    private val jsPostRegex = Regex("""(?i)(?:post|post_id|movie_id|id)\s*[:=]\s*['\"]?(\d{2,})""")
    private val bodyPostRegex = Regex("""(?i)(?:postid-|post-|wp-post-)(\d{2,})""")
    private val ajaxUrlRegex = Regex("""(?i)(?:ajaxurl|ajax_url|admin_ajax|adminajax|ajaxUrl)\s*[=:]\s*[\'\"]([^\'\"]*admin-ajax\.php[^\'\"]*)[\'\"]""")
    private val anyAdminAjaxRegex = Regex("""(?i)(?:https?:)?//[^\'\"<>\s]+admin-ajax\.php[^\'\"<>\s]*|/[^\'\"<>\s]*admin-ajax\.php[^\'\"<>\s]*""")
    private val nonceRegex = Regex("""(?i)(?:nonce|security|_wpnonce|player_nonce|doo_nonce|dtNonce|playnonce)\s*[=:]\s*[\'\"]([A-Za-z0-9_\-]{5,})[\'\"]""")

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
            app.get(pageUrl, headers = CgvIndoUtils.siteHeadersFor(referer), referer = referer).document
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
            val customFound = runCustomHostExtractor(providerName, embed, pageUrl, emitted, subtitleCallback, callback)
            val extractorFound = customFound || runExtractor(embed, pageUrl, emitted, subtitleCallback, callback)
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
        val security = securityFields(document)
        if (options.isEmpty()) Log.e(TAG, "AJAX no player options found")
        val out = linkedSetOf<String>()
        val actions = listOf(
            "doo_player_ajax", "dt_player_ajax", "player_ajax", "get_player", "get_video",
            "get_embed", "getEmbed", "get_player_content", "player", "getplayer"
        )
        val fallbackTypes = inferTypes(pageUrl)
        var probes = 0

        for (endpoint in endpoints) {
            for (option in options) {
                val postValues = listOfNotNull(option.post)
                    .filter { it.isNotBlank() }
                    .distinct()
                val numeValues = listOfNotNull(option.nume, option.server)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .ifEmpty { listOf("1") }
                val typeValues = listOfNotNull(option.type)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .ifEmpty { fallbackTypes }

                for (action in actions) {
                    for (post in postValues) {
                        for (nume in numeValues) {
                            for (type in typeValues) {
                                if (probes++ >= MAX_AJAX_PROBES) {
                                    Log.e(TAG, "AJAX probe cap reached candidates=${out.size}")
                                    return out.toList()
                                }
                                val forms = ajaxBodies(action, post, nume, type, option, security)
                                forms.forEach { body ->
                                    val encodedBody = body.entries.joinToString("&") { (key, value) ->
                                        "${key}=${URLEncoder.encode(value, "UTF-8")}"
                                    }
                                    val response = runCatching {
                                        val requestBody = encodedBody.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull())
                                        val res = app.post(endpoint, requestBody = requestBody, headers = CgvIndoUtils.ajaxHeaders(pageUrl), referer = pageUrl)
                                        Log.e(TAG, "AJAX POST endpoint=$endpoint action=$action post=$post nume=$nume type=$type status=${res.code} body=${res.text.take(300)}")
                                        res.text
                                    }.getOrNull()

                                    if (!response.isNullOrBlank()) {
                                        ajaxPayloadUrls(pageUrl, response).forEach { out.add(it) }
                                    }

                                    if (response.isNullOrBlank() || response.trim() == "0") {
                                        val getResponse = runCatching {
                                            val getUrl = "$endpoint?$encodedBody"
                                            val res = app.get(getUrl, headers = CgvIndoUtils.ajaxHeaders(pageUrl), referer = pageUrl)
                                            Log.e(TAG, "AJAX GET endpoint=$getUrl status=${res.code} body=${res.text.take(300)}")
                                            res.text
                                        }.getOrNull()
                                        if (!getResponse.isNullOrBlank()) {
                                            ajaxPayloadUrls(pageUrl, getResponse).forEach { out.add(it) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Log.e(TAG, "AJAX captured candidates=${out.size} sample=${out.take(8)}")
        return out.toList()
    }

    private fun ajaxBodies(
        action: String,
        post: String,
        nume: String,
        type: String,
        option: CgvIndoPlayerOption,
        security: Map<String, String>
    ): List<Map<String, String>> {
        fun withSecurity(map: LinkedHashMap<String, String>): Map<String, String> {
            security.forEach { (key, value) -> map[key] = value }
            option.server?.takeIf { it.isNotBlank() }?.let { map.putIfAbsent("server", it) }
            return map
        }
        val base = linkedMapOf("action" to action, "post" to post, "nume" to nume, "type" to type)
        val alt = linkedMapOf("action" to action, "id" to post, "server" to nume, "type" to type)
        val alt2 = linkedMapOf("action" to action, "post_id" to post, "server" to nume, "episode" to nume, "type" to type)
        val alt3 = linkedMapOf("action" to action, "movie_id" to post, "nume" to nume, "type" to type)
        return listOf(withSecurity(base), withSecurity(alt), withSecurity(alt2), withSecurity(alt3)).distinct()
    }

    private fun ajaxEndpoints(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        val origin = CgvIndoUtils.originOf(pageUrl) ?: CgvIndoSeeds.MAIN_URL
        out.add("$origin/wp-admin/admin-ajax.php")
        val html = normalizedHtml(document)
        ajaxUrlRegex.findAll(html)
            .mapNotNull { CgvIndoUtils.absoluteUrl(pageUrl, it.groupValues[1]) }
            .forEach { out.add(it) }
        anyAdminAjaxRegex.findAll(html)
            .mapNotNull { CgvIndoUtils.absoluteUrl(pageUrl, it.value) }
            .forEach { out.add(it) }
        document.select("[data-ajax], [data-ajaxurl], [data-admin-ajax], form[action]").forEach { el ->
            listOf("data-ajax", "data-ajaxurl", "data-admin-ajax", "action").forEach { attr ->
                CgvIndoUtils.absoluteUrl(pageUrl, el.attr(attr))?.let { if ("admin-ajax" in it.lowercase()) out.add(it) }
            }
        }
        return out.toList()
    }

    private fun playerOptions(pageUrl: String, document: Document): List<CgvIndoPlayerOption> {
        val out = mutableListOf<CgvIndoPlayerOption>()
        val globalPost = globalPostId(document)
        val html = normalizedHtml(document)

        document.select(optionSelectors).forEach { el ->
            val explicitPost = listOf(
                el.attr("data-post"), el.attr("data-id"), el.attr("data-movie"),
                el.attr("data-movieid"), el.attr("data-postid"), el.attr("data-post-id"), el.attr("data-movie-id")
            ).firstOrNull { it.isNotBlank() && it.all { ch -> ch.isDigit() } }
            val explicitNume = listOf(
                el.attr("data-nume"), el.attr("data-server"), el.attr("data-serverid"),
                el.attr("data-episode"), el.attr("value")
            ).firstOrNull { it.isNotBlank() && it.all { ch -> ch.isDigit() } }
            val type = listOf(el.attr("data-type"), el.attr("data-player"), el.attr("data-kind"))
                .firstOrNull { it.isNotBlank() }
            val idClass = "${el.id()} ${el.className()} ${el.attr("onclick")} ${el.attr("data-option")}" 
            val fallback = Regex("(?i)(?:player-option|dooplay_player_option|player_option|option)[-_]?(\\d+)(?:[-_](\\d+))?").find(idClass)
            val first = fallback?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            val second = fallback?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }

            val post = explicitPost ?: globalPost ?: if (second != null) first else null
            val nume = explicitNume ?: second ?: if (post != first) first else null ?: el.attr("data-server").takeIf { it.isNotBlank() } ?: "1"
            if (!post.isNullOrBlank()) {
                out.add(CgvIndoPlayerOption(post, nume, type, el.attr("data-server").takeIf { it.isNotBlank() }, CgvIndoUtils.cleanText(el.text())))
            }
        }

        // Regex fallback for inline dooplay options inside scripts or escaped HTML.
        val global = globalPost
        if (!global.isNullOrBlank()) {
            val numbers = linkedSetOf<String>()
            dataNumeRegex.findAll(html).map { it.groupValues[1] }.forEach { numbers.add(it) }
            Regex("(?i)(?:player-option|dooplay_player_option|player_option)[-_]?(\\d+)").findAll(html)
                .map { it.groupValues[1] }
                .forEach { numbers.add(it) }
            if (numbers.isEmpty()) numbers.addAll(listOf("1", "2", "3"))
            val types = dataTypeRegex.find(html)?.groupValues?.getOrNull(1)?.let { listOf(it) } ?: inferTypes(pageUrl)
            numbers.take(8).forEach { nume ->
                types.forEach { type -> out.add(CgvIndoPlayerOption(global, nume, type, nume)) }
            }
        }

        if (out.isEmpty()) {
            val post = globalPost
                ?: jsPostRegex.find(html)?.groupValues?.getOrNull(1)
                ?: dataPostRegex.find(html)?.groupValues?.getOrNull(1)
            if (!post.isNullOrBlank()) {
                inferTypes(pageUrl).forEach { type -> out.add(CgvIndoPlayerOption(post, "1", type)) }
            }
        }

        Log.e(TAG, "AJAX player options globalPost=$globalPost count=${out.size} sample=${out.take(6)}")
        return out.distinctBy { "${it.post}|${it.nume}|${it.type}|${it.server}" }
    }

    private fun globalPostId(document: Document): String? {
        val html = normalizedHtml(document)
        val bodyClass = document.body()?.className().orEmpty()
        return bodyPostRegex.find(bodyClass)?.groupValues?.getOrNull(1)
            ?: document.selectFirst("article[id^=post-], div[id^=post-]")?.id()?.let { bodyPostRegex.find(it)?.groupValues?.getOrNull(1) }
            ?: document.selectFirst("input[name=post], input[name=post_id], input[name=movie_id], [data-post], [data-postid]")?.let { el ->
                listOf(el.attr("value"), el.attr("data-post"), el.attr("data-postid"), el.attr("data-id")).firstOrNull { it.isNotBlank() && it.all { ch -> ch.isDigit() } }
            }
            ?: bodyPostRegex.find(html)?.groupValues?.getOrNull(1)
            ?: jsPostRegex.find(html)?.groupValues?.getOrNull(1)
            ?: dataPostRegex.find(html)?.groupValues?.getOrNull(1)
    }

    private fun securityFields(document: Document): Map<String, String> {
        val html = normalizedHtml(document)
        val nonce = nonceRegex.find(html)?.groupValues?.getOrNull(1)
            ?: document.selectFirst("input[name=nonce], input[name=security], input[name=_wpnonce], [data-nonce], [data-security]")?.let { el ->
                listOf(el.attr("value"), el.attr("data-nonce"), el.attr("data-security")).firstOrNull { it.isNotBlank() }
            }
        return if (!nonce.isNullOrBlank()) mapOf("nonce" to nonce, "security" to nonce, "_wpnonce" to nonce) else emptyMap()
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
                is JSONArray -> collectJsonArray(pageUrl, value, out)
            }
        }
    }

    private fun collectJsonArray(pageUrl: String, array: JSONArray, out: MutableSet<String>) {
        for (i in 0 until array.length()) {
            when (val value = array.opt(i)) {
                is String -> ajaxPayloadUrls(pageUrl, value).forEach { out.add(it) }
                is JSONObject -> collectJson(pageUrl, value, out)
                is JSONArray -> collectJsonArray(pageUrl, value, out)
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
            low.contains("filemoon") || low.contains("vidhide") || low.contains("vidguard") ||
                low.contains("streamwish") || low.contains("filelions") || low.contains("streamruby") ||
                low.contains("uqload") || low.contains("voe") || low.contains("mp4upload") ->
                extractPackedPlayer(providerName, url, referer, emitted, subtitleCallback, callback)
            else -> false
        }
    }

    private suspend fun extractPackedPlayer(
        providerName: String,
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching { app.get(url, headers = CgvIndoUtils.siteHeadersFor(referer), referer = referer).text }
            .onFailure { Log.e(TAG, "custom packed GET failed $url: ${it.message}") }
            .getOrNull() ?: return false
        val normalized = CgvIndoUtils.decodeMaybe(html).replace("&lt;", "<").replace("&gt;", ">")
        val candidates = prioritizeCandidates(
            extractUrlsFromHtml(url, normalized) +
                unpackPackedScripts(normalized).flatMap { extractUrlsFromHtml(url, it) }
        )
        var found = false
        for (candidate in candidates.take(24)) {
            val directFound = emitDirect(providerName, candidate, url, emitted, callback)
            val extractorFound = if (!directFound && looksLikeEmbed(candidate)) runExtractor(candidate, url, emitted, subtitleCallback, callback) else false
            found = found || directFound || extractorFound
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
        val html = runCatching { app.get(url, headers = CgvIndoUtils.siteHeadersFor(referer), referer = referer).text }
            .onFailure { Log.e(TAG, "streamtape GET failed $url: ${it.message}") }
            .getOrNull() ?: return false
        val direct = linkedSetOf<String>()
        Regex("""(?i)(?:https?:)?//[^'\"<>\s]+?/get_video\?[^'\"<>\s]+""")
            .findAll(CgvIndoUtils.decodeMaybe(html))
            .mapNotNull { normalizeUrl(url, it.value) }
            .forEach { direct.add(it) }
        var found = false
        direct.forEach { finalUrl ->
            if (emitted.add(finalUrl)) {
                callback(newExtractorLink(providerName, "$providerName StreamTape", finalUrl) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = CgvIndoUtils.videoHeaders(url)
                })
                found = true
            }
        }
        return found
    }

    private suspend fun extractDood(
        providerName: String,
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = CgvIndoUtils.originOf(url) ?: return false
        val html = runCatching { app.get(url, headers = CgvIndoUtils.siteHeadersFor(referer), referer = referer).text }
            .onFailure { Log.e(TAG, "dood GET failed $url: ${it.message}") }
            .getOrNull() ?: return false
        val passPath = Regex("""(/pass_md5/[^'\"<>\s]+)""").find(html)?.groupValues?.getOrNull(1) ?: return false
        val token = Regex("""token=([^&'\"<>\s]+)""").find(passPath)?.groupValues?.getOrNull(1).orEmpty()
        val seed = runCatching { app.get(origin.trimEnd('/') + passPath, headers = CgvIndoUtils.videoHeaders(url), referer = url).text }
            .getOrNull()
            ?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return false
        val random = (1..10).joinToString("") { ('a'..'z').random().toString() }
        val finalUrl = seed + random + if (token.isNotBlank()) "?token=$token&expiry=${System.currentTimeMillis()}" else ""
        if (emitted.add(finalUrl)) {
            callback(newExtractorLink(providerName, "$providerName Dood", finalUrl) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = CgvIndoUtils.videoHeaders(url)
            })
            return true
        }
        return false
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
        bareMediaRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.value) }.filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
        iframeRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksLikeDirectOrHls(it) }.forEach { out.add(it) }
        encodedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.value) }.filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
        decodeBase64Candidates(html).mapNotNull { normalizeUrl(pageUrl, it) }.filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
        unpackPackedScripts(html).flatMap { extractUrlsFromHtml(pageUrl, it) }.filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
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


    private fun unpackPackedScripts(html: String): List<String> {
        return packedEvalRegex.findAll(html).mapNotNull { match ->
            runCatching {
                val payload = match.groupValues[1]
                val radix = match.groupValues[2].toInt()
                val count = match.groupValues[3].toInt()
                val words = match.groupValues[4].split('|')
                var decoded = payload
                for (i in count - 1 downTo 0) {
                    val word = words.getOrNull(i).orEmpty()
                    if (word.isNotEmpty()) {
                        decoded = Regex("\\b${i.toString(radix)}\\b").replace(decoded, word)
                    }
                }
                CgvIndoUtils.decodeMaybe(decoded)
            }.getOrNull()
        }.toList()
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
        return knownHost(low) || listOf("/embed", "/player", "iframe", "stream", "filemoon", "dood", "streamtape", "streamwish", "vidhide", "vidguard", "short.ink", "mp4upload").any { it in low }
    }

    private fun knownHost(url: String): Boolean {
        val low = url.lowercase()
        return listOf(
            "streamtape", "dood", "doodstream", "filemoon", "vidhide", "vidguard", "streamwish", "filelions", "streamruby",
            "mp4upload", "upstream", "mixdrop", "streamsb", "sbembed", "luluvdo", "voe", "uqload", "short.ink", "hydrax", "streamvid", "streamhide", "filegram", "fileditch", "vidmoly",
            "hglink", "hgcloud", "ghbrisk", "dhcplay", "streamcasthub", "dm21", "embed4me", "upns", "p2pplay", "4meplayer", "meplayer",
            "dingtezuni", "dintezuvio", "minochinos", "mivalyo", "movearnpre", "ryderjet", "bingezove", "jeniusplay", "veev", "kinoger", "poophq"
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


// Region: custom extractor layer adopted from working BetbetMiro providers.
private fun fixCgvExtractorUrlSafe(url: String, baseUrl: String): String {
    val cleanUrl = url.trim()
        .replace("\\/", "/")
        .replace("&amp;", "&")

    return when {
        cleanUrl.startsWith("http://", ignoreCase = true) ||
            cleanUrl.startsWith("https://", ignoreCase = true) -> cleanUrl
        cleanUrl.startsWith("//") -> "https:$cleanUrl"
        cleanUrl.startsWith("/") -> baseUrl.trimEnd('/') + cleanUrl
        else -> baseUrl.trimEnd('/') + "/" + cleanUrl.trimStart('/')
    }
}

class Movearnpre : Dingtezuni() {
    override var name = "Movearnpre"
    override var mainUrl = "https://movearnpre.com"
}

class Minochinos : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://minochinos.com"
}

class Mivalyo : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://mivalyo.com"
}

class Ryderjet : Dingtezuni() {
    override var name = "Ryderjet"
    override var mainUrl = "https://ryderjet.com"
}

class Bingezove : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://bingezove.com"
}

open class Dingtezuni : ExtractorApi() {
    override val name = "Earnvids"
    override val mainUrl = "https://dingtezuni.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )

        val response = app.get(getEmbedUrl(url), referer = referer)
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            var result = getAndUnpack(response.text)
            if (result.contains("var links")) result = result.substringAfter("var links")
            result
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        } ?: return

        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { m3u8Match ->
            val streamUrl = fixCgvExtractorUrlSafe(m3u8Match.groupValues[1], mainUrl)
            generateM3u8(name, streamUrl, referer = "$mainUrl/", headers = headers).forEach(callback)
        }
    }

    private fun getEmbedUrl(url: String): String {
        return when {
            url.contains("/d/") -> url.replace("/d/", "/v/")
            url.contains("/download/") -> url.replace("/download/", "/v/")
            url.contains("/file/") -> url.replace("/file/", "/v/")
            else -> url.replace("/f/", "/v/")
        }
    }
}

class Hglink : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}

class Ghbrisk : StreamWishExtractor() {
    override val name = "Ghbrisk"
    override val mainUrl = "https://ghbrisk.com"
}

class Hgcloud : StreamWishExtractor() {
    override val name = "Hgcloud"
    override val mainUrl = "https://hgcloud.to"
}

class Dhcplay : StreamWishExtractor() {
    override var name = "DHC Play"
    override var mainUrl = "https://dhcplay.com"
}

class Streamcasthub : VidStack() {
    override var name = "Streamcasthub"
    override var mainUrl = "https://live.streamcasthub.store"
    override var requiresReferer = true
}

class Dm21embed : VidStack() {
    override var name = "Dm21embed"
    override var mainUrl = "https://dm21.embed4me.vip"
    override var requiresReferer = true
}

class Dm21upns : VidStack() {
    override var name = "Dm21upns"
    override var mainUrl = "https://dm21.upns.live"
    override var requiresReferer = true
}

class Pm21p2p : VidStack() {
    override var name = "Pm21p2p"
    override var mainUrl = "https://pm21.p2pplay.pro"
    override var requiresReferer = true
}

class Dm21 : VidStack() {
    override var name = "Dm21"
    override var mainUrl = "https://dm21.embed4me.vip"
    override var requiresReferer = true
}

class Meplayer : VidStack() {
    override var name = "Meplayer"
    override var mainUrl = "https://video.4meplayer.com"
    override var requiresReferer = true
}

open class Dintezuvio : Dingtezuni() {
    override val name = "Earnvids"
    override val mainUrl = "https://dintezuvio.com"
}

class Veev : ExtractorApi() {
    override val name = "Veev"
    override val mainUrl = "https://veev.to"
    override val requiresReferer = false

    private val pattern = Regex("""(?://|\.)(?:veev|kinoger|poophq|doods)\.(?:to|pw|com)/[ed]/([0-9A-Za-z]+)""")

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mediaId = pattern.find(url)?.groupValues?.get(1) ?: return
        val pageUrl = "$mainUrl/e/$mediaId"

        val html = app.get(pageUrl, headers = mapOf("User-Agent" to DEFAULT_UA)).text
        val encRegex = Regex("""[.\s'](?:fc|_vvto\[[^]]*)(?:['\]]*)?\s*[:=]\s*['"]([^'"]+)""")
        val foundValues = encRegex.findAll(html).map { it.groupValues[1] }.toList()
        if (foundValues.isEmpty()) return

        for (f in foundValues.reversed()) {
            val ch = veevDecode(f)
            if (ch == f) continue

            val dlUrl = "$mainUrl/dl?op=player_api&cmd=gi&file_code=$mediaId&r=$mainUrl&ch=$ch&ie=1"
            val responseText = app.get(dlUrl, headers = mapOf("User-Agent" to DEFAULT_UA)).text

            val json = runCatching { JSONObject(responseText) }.getOrNull() ?: continue
            val file = json.optJSONObject("file") ?: continue
            if (file.optString("file_status") != "OK") continue

            val dv = file.getJSONArray("dv").getJSONObject(0).getString("s")
            val decoded = decodeUrl(veevDecode(dv), buildArray(ch).firstOrNull().orEmpty())

            callback.invoke(
                newExtractorLink(name, name, decoded, INFER_TYPE) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }
    }

    fun String.toExoPlayerMimeType(): String {
        return when (this.lowercase()) {
            "video/x-matroska", "video/webm" -> HlsPlaylistParser.MimeTypes.VIDEO_MATROSKA
            "video/mp4" -> HlsPlaylistParser.MimeTypes.VIDEO_MP4
            "application/x-mpegurl", "application/vnd.apple.mpegurl" -> HlsPlaylistParser.MimeTypes.APPLICATION_M3U8
            "video/avi" -> HlsPlaylistParser.MimeTypes.VIDEO_AVI
            else -> ""
        }
    }

    private fun veevDecode(etext: String): String {
        if (etext.isEmpty()) return etext
        val result = StringBuilder()
        val lut = HashMap<Int, String>()
        var n = 256
        var c = etext[0].toString()
        result.append(c)
        for (char in etext.drop(1)) {
            val code = char.code
            val nc = if (code < 256) char.toString() else lut[code] ?: (c + c[0])
            result.append(nc)
            lut[n++] = c + nc[0]
            c = nc
        }
        return result.toString()
    }

    private fun jsInt(x: Char): Int = x.digitToIntOrNull() ?: 0

    private fun buildArray(encoded: String): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        val it = encoded.iterator()
        fun nextIntOrZero(): Int = if (it.hasNext()) jsInt(it.nextChar()) else 0
        var count = nextIntOrZero()
        while (count != 0) {
            val row = mutableListOf<Int>()
            repeat(count) { row.add(nextIntOrZero()) }
            result.add(row.reversed())
            count = nextIntOrZero()
        }
        return result
    }

    private fun decodeUrl(encoded: String, rules: List<Int>): String {
        var text = encoded
        for (r in rules) {
            if (r == 1) text = text.reversed()
            val arr = text.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
            text = arr.toString(Charsets.UTF_8).replace("dXRmOA==", "")
        }
        return text
    }
}

class Jeniusplay : ExtractorApi() {
    override var name = "Jeniusplay"
    override var mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        val hash = url.split("/").last().substringAfter("data=")

        val m3uLink = runCatching {
            val response = app.post(
                url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
                data = mapOf("hash" to hash, "r" to referer.orEmpty()),
                referer = referer,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text
            JSONObject(response).optString("videoSource").takeIf { it.isNotBlank() }
        }.getOrNull()

        if (m3uLink != null) {
            callback.invoke(newExtractorLink(name, name, m3uLink, ExtractorLinkType.M3U8))
        }

        document.select("script").forEach { script ->
            if (script.data().contains("eval(function(p,a,c,k,e,d)")) {
                val subData = getAndUnpack(script.data()).substringAfter("\"tracks\":[").substringBefore("],")
                runCatching { JSONArray("[$subData]") }.getOrNull()?.let { array ->
                    for (i in 0 until array.length()) {
                        val subtitle = array.optJSONObject(i) ?: continue
                        val file = subtitle.optString("file")
                        val label = subtitle.optString("label")
                        if (file.isNotBlank()) subtitleCallback.invoke(newSubtitleFile(getLanguage(label), file))
                    }
                }
            }
        }
    }

    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) || str.contains("bahasa", true) -> "Indonesian"
            else -> str
        }
    }

    data class JeniusResponseSource(
        @param:JsonProperty("hls") val hls: Boolean,
        @param:JsonProperty("videoSource") val videoSource: String,
        @param:JsonProperty("securedLink") val securedLink: String?,
    )
}
