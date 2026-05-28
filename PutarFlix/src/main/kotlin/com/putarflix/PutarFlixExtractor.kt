package com.putarflix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object PutarFlixExtractor {
    private const val MAX_RESOLVE_DEPTH = 3

    private val directVideoRegex = Regex("""https?:\\?/\\?/[^\"'<>\s]+?\.(?:m3u8|mp4|mkv|mpd)(?:\?[^\"'<>\s]+)?""", RegexOption.IGNORE_CASE)
    private val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val jsonEmbedRegex = Regex("""["'](?:embed_url|file|url|source|src)["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private val playerContainers = listOf(
        "#player", "#player2", "#video", ".player", ".player-area", ".playex",
        ".movieplay", ".video-content", ".responsive-embed", ".embed-responsive",
        ".pembed", ".dooplay_player", ".dooplay_player_content", ".server",
        ".servers", ".server-item", ".player-option", ".download", ".dllinks"
    ).joinToString(",")

    suspend fun extract(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startUrl = PutarFlixUtils.decodeKnownRedirect(data.trim())
        if (startUrl.isBlank()) return false

        if (!PutarFlixUtils.isPutarFlixUrl(startUrl)) {
            if (PutarFlixUtils.looksDirectVideo(startUrl)) {
                return emitDirect(startUrl, PutarFlixSeeds.MAIN_URL, "PutarFlix Direct", callback)
            }
            return resolveServer(startUrl, PutarFlixSeeds.MAIN_URL, "PutarFlix External", subtitleCallback, callback)
        }

        val playerPages = buildList {
            add(startUrl)
            val clean = startUrl.substringBefore("?")
            PutarFlixSeeds.playerNumbers.forEach { number ->
                if (number == "1") add(clean) else add("$clean?player=$number")
            }
        }.distinct()

        val candidates = linkedSetOf<PutarFlixServer>()
        for (page in playerPages) {
            val doc = runCatching { app.get(page, referer = PutarFlixSeeds.MAIN_URL).document }.getOrNull() ?: continue
            candidates += collectServersFromDocument(page, doc)
            candidates += collectAjaxServers(page, doc)
        }

        var found = false
        val visited = linkedSetOf<String>()
        for (server in candidates.distinctBy { it.url }) {
            val finalUrl = PutarFlixUtils.decodeKnownRedirect(server.url)
            if (shouldSkipCandidate(finalUrl, allowPlayerPage = false)) continue
            found = resolveServer(finalUrl, server.referer, server.label, subtitleCallback, callback, visited) || found
            if (found) break
        }
        return found
    }

    private fun collectServersFromDocument(pageUrl: String, doc: Document): List<PutarFlixServer> {
        val servers = linkedSetOf<PutarFlixServer>()

        // Iframe/video/source are real player carriers. Scan them globally because themes often keep
        // the actual embed outside the visible server list.
        doc.select("iframe[src], embed[src], video[src], source[src]").forEach { element ->
            addServerFromElement(servers, pageUrl, element, allowInternalPlayerPage = false)
        }

        // Anchor scanning is intentionally limited to player/download containers so menus,
        // categories, related posts, and the same PutarFlix detail page do not cause recursive loops.
        doc.select(playerContainers).forEach { container ->
            container.select("a[href], button, div, li, span").forEach { element ->
                addServerFromElement(servers, pageUrl, element, allowInternalPlayerPage = true)
            }
        }

        val scriptText = doc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
        directVideoRegex.findAll(scriptText).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.value) ?: return@forEach
            if (!shouldSkipCandidate(url, allowPlayerPage = false)) {
                servers += PutarFlixServer("PutarFlix Direct", url, pageUrl, "script-direct")
            }
        }
        jsonEmbedRegex.findAll(scriptText).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEach
            if (!shouldSkipCandidate(url, allowPlayerPage = false)) {
                servers += PutarFlixServer("PutarFlix Embed", url, pageUrl, "script-json")
            }
        }
        PutarFlixUtils.extractUrlsFromText(pageUrl, scriptText).forEach { url ->
            if (!shouldSkipCandidate(url, allowPlayerPage = false)) {
                servers += PutarFlixServer("PutarFlix Script", url, pageUrl, "script-url")
            }
        }

        return servers.distinctBy { it.url }
    }

    private fun addServerFromElement(
        servers: MutableSet<PutarFlixServer>,
        pageUrl: String,
        element: Element,
        allowInternalPlayerPage: Boolean
    ) {
        val raw = firstAttr(
            element,
            "src", "data-src", "data-lazy-src", "data-iframe", "data-embed", "data-link",
            "data-url", "data-video", "data-file", "data-href", "href"
        ) ?: return
        val url = PutarFlixUtils.absoluteUrl(pageUrl, raw) ?: return
        if (shouldSkipCandidate(url, allowPlayerPage = allowInternalPlayerPage)) return
        servers += PutarFlixServer(PutarFlixUtils.extractLabelNear(element), url, pageUrl, element.tagName())
    }

    private suspend fun collectAjaxServers(pageUrl: String, doc: Document): List<PutarFlixServer> {
        val players = collectAjaxPlayers(pageUrl, doc)
        if (players.isEmpty()) return emptyList()

        val output = linkedSetOf<PutarFlixServer>()
        for (player in players) {
            for (action in PutarFlixSeeds.ajaxActions) {
                val response = runCatching {
                    app.post(
                        "${PutarFlixSeeds.MAIN_URL}/wp-admin/admin-ajax.php",
                        referer = pageUrl,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                        ),
                        data = mapOf(
                            "action" to action,
                            "post" to player.postId,
                            "nume" to player.nume,
                            "type" to player.type
                        )
                    ).text
                }.getOrNull() ?: continue

                val found = collectServersFromAjaxText(pageUrl, response, player.label)
                if (found.isNotEmpty()) {
                    output += found
                    break
                }
            }
        }
        return output.distinctBy { it.url }
    }

    private fun collectAjaxPlayers(pageUrl: String, doc: Document): List<PutarFlixAjaxPlayer> {
        val players = linkedSetOf<PutarFlixAjaxPlayer>()
        val fallbackType = if (pageUrl.contains("/tv/") || pageUrl.contains("/eps/")) "tv" else "movie"

        doc.select("[data-post][data-nume], [data-type][data-post], .dooplay_player_option, li[id*=player-option], .server-item[data-id]")
            .forEach { element ->
                val post = firstAttr(element, "data-post", "data-id", "data-postid", "data-post-id") ?: return@forEach
                val nume = firstAttr(element, "data-nume", "data-server", "data-player", "data-number", "data-no") ?: return@forEach
                val type = firstAttr(element, "data-type") ?: fallbackType
                players += PutarFlixAjaxPlayer(post, type, nume, PutarFlixUtils.extractLabelNear(element))
            }

        return players.distinctBy { "${it.postId}:${it.type}:${it.nume}" }
    }

    private fun collectServersFromAjaxText(pageUrl: String, response: String, label: String): List<PutarFlixServer> {
        val decoded = PutarFlixUtils.decodeUrlRepeated(response)
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\\"", "\"")

        val output = linkedSetOf<PutarFlixServer>()
        jsonEmbedRegex.findAll(decoded).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEach
            if (!shouldSkipCandidate(url, allowPlayerPage = false)) {
                output += PutarFlixServer(label, url, pageUrl, "ajax-json")
            }
        }
        iframeRegex.findAll(decoded).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEach
            if (!shouldSkipCandidate(url, allowPlayerPage = false)) {
                output += PutarFlixServer(label, url, pageUrl, "ajax-iframe")
            }
        }
        PutarFlixUtils.extractUrlsFromText(pageUrl, decoded).forEach { url ->
            if (!shouldSkipCandidate(url, allowPlayerPage = false)) {
                output += PutarFlixServer(label, url, pageUrl, "ajax-url")
            }
        }

        val htmlDoc = Jsoup.parse(decoded, pageUrl)
        htmlDoc.select("iframe[src], embed[src], source[src], video[src]").forEach { element ->
            addServerFromElement(output, pageUrl, element, allowInternalPlayerPage = false)
        }
        return output.distinctBy { it.url }
    }

    private suspend fun resolveServer(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String> = linkedSetOf(),
        depth: Int = 0
    ): Boolean {
        val fixedUrl = PutarFlixUtils.decodeKnownRedirect(url)
        if (depth > MAX_RESOLVE_DEPTH || fixedUrl in visited) return false
        visited += fixedUrl

        if (PutarFlixUtils.looksDirectVideo(fixedUrl)) {
            return emitDirect(fixedUrl, referer, label, callback)
        }
        if (shouldSkipCandidate(fixedUrl, allowPlayerPage = false)) return false

        val loaded = runCatching { loadExtractor(fixedUrl, referer, subtitleCallback, callback) }.getOrDefault(false)
        if (loaded) return true

        val doc = runCatching { app.get(fixedUrl, referer = referer).document }.getOrNull() ?: return false
        val nested = collectServersFromDocument(fixedUrl, doc)
        var found = false
        for (server in nested.distinctBy { it.url }) {
            val nestedUrl = PutarFlixUtils.decodeKnownRedirect(server.url)
            if (shouldSkipCandidate(nestedUrl, allowPlayerPage = false)) continue
            found = resolveServer(nestedUrl, fixedUrl, server.label, subtitleCallback, callback, visited, depth + 1) || found
            if (found) break
        }
        return found
    }

    private fun shouldSkipCandidate(url: String, allowPlayerPage: Boolean): Boolean {
        if (PutarFlixUtils.isRejectedVideoCandidate(url)) return true
        if (!allowPlayerPage && PutarFlixUtils.isInternalNavigation(url)) return true
        if (PutarFlixUtils.isPutarFlixUrl(url) && !PutarFlixUtils.looksDirectVideo(url) && !url.contains("?player=")) return true
        return false
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val type = when {
            url.substringBefore("?").endsWith(".m3u8", true) -> ExtractorLinkType.M3U8
            url.substringBefore("?").endsWith(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        callback.invoke(
            newExtractorLink(
                source = "PutarFlix",
                name = label.ifBlank { "PutarFlix" },
                url = url,
                type = type
            ) {
                this.referer = referer
                this.quality = getQualityFromName(url)
            }
        )
        return true
    }

    private fun firstAttr(element: Element, vararg attrs: String): String? {
        return attrs.firstNotNullOfOrNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
    }
}
