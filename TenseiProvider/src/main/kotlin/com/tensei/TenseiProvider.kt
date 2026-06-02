package com.tensei

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class TenseiProvider : MainAPI() {
    override var mainUrl = "https://tensei.club"
    override var name = "Tensei ID"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "/" to "Latest Release",
        "/anime/?status=&type=&order=update" to "Update Anime",
        "/anime/?status=&type=&order=latest" to "Anime Terbaru",
        "/anime/?status=ongoing&type=&order=update" to "Ongoing",
        "/anime/?status=completed&type=&order=update" to "Completed",
        "/anime/?status=&type=movie&order=update" to "Movie",
        "/anime/?status=&type=ova&order=update" to "OVA",
        "/anime/?status=&type=&order=popular" to "Popular",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val document = app.get(pageUrl(request.data, page), referer = "$mainUrl/").document
        val items = document.toSearchResults()
        val hasNext = document.select(
            ".pagination a.next, a.next.page-numbers, .hpage a.r, a[href*='/page/${page + 1}/']"
        ).isNotEmpty()

        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return app.get("$mainUrl/?s=$encoded", referer = "$mainUrl/").document.toSearchResults()
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val fixedUrl = normalizeUrl(url, mainUrl) ?: url
        val seriesUrl = if (fixedUrl.contains("/anime/", true)) {
            fixedUrl
        } else {
            app.get(fixedUrl, referer = "$mainUrl/").document
                .selectFirst(".year a[href*='/anime/'], .naveps a[href*='/anime/'], a[href*='/anime/']")
                ?.attr("href")
                ?.let { normalizeUrl(it, fixedUrl) }
                ?: fixedUrl
        }
        val document = app.get(seriesUrl, referer = "$mainUrl/").document

        val title = document.selectFirst(".animefull h1.entry-title, h1.entry-title, meta[property=og:title], title")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst(
            ".animefull .thumb img, .single-info .thumb img, meta[property=og:image], img.wp-post-image"
        )?.imageUrl()?.fixImageQuality()
        val background = document.selectFirst(".bigcover img, meta[property=og:image]")?.imageUrl()?.fixImageQuality()
        val type = getType(document.infoText("Type"), seriesUrl)
        val year = document.infoText("Released")?.extractYear()
        val rating = document.selectFirst("meta[itemprop=ratingValue], .rating strong")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.toScore()
        val status = getStatus(document.infoText("Status"))
        val tags = document.select(".genxed a[href*='/genres/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val plot = document.selectFirst(".bixbox.synp .entry-content, .entry-content[itemprop=description], .desc.mindes")
            ?.cleanTextBlock()
        val episodes = document.select(".eplister ul li a[href], .eplister a[href]")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
        val recommendations = document.select(".listupd article.bs, .listupd .stylefiv, .listupd .bsx")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == seriesUrl }
            .distinctBy { it.url }

        if (type == TvType.AnimeMovie && episodes.size <= 1) {
            val data = episodes.firstOrNull()?.data ?: seriesUrl
            return newMovieLoadResponse(title, seriesUrl, type, data) {
                posterUrl = poster
                backgroundPosterUrl = background ?: poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                rating?.let { score = Score.from10(it) }
                this.recommendations = recommendations
            }
        }

        return newAnimeLoadResponse(title, seriesUrl, type) {
            posterUrl = poster
            backgroundPosterUrl = background ?: poster
            this.year = year
            plot?.let { this.plot = it }
            this.tags = tags
            rating?.let { score = Score.from10(it) }
            showStatus = status
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes.ifEmpty { listOf(newEpisode(seriesUrl)) })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val fixedUrl = normalizeUrl(data, mainUrl) ?: data
        val document = app.get(fixedUrl, referer = "$mainUrl/").document
        val emitted = linkedSetOf<String>()

        document.select("#embed_holder iframe[src], .player-embed iframe[src], .video-content iframe[src], iframe[src]")
            .mapNotNull { it.iframeUrl(fixedUrl) }
            .forEach { resolveMirrorLink(it, "Player", fixedUrl, emitted, subtitleCallback, callback) }

        document.select("select.mirror option[value], .mirror option[value]").forEach { option ->
            val label = option.text().cleanWhitespace().ifBlank { "Mirror" }
            if (label.contains("select video", true)) return@forEach
            decodeMirrorLinks(option.attr("value")).forEach { mirrorUrl ->
                resolveMirrorLink(mirrorUrl, label, fixedUrl, emitted, subtitleCallback, callback)
            }
        }

        document.select(".soraurlx a[href], .soraddlx a[href], .download a[href], a[href*='filedon.co/view/']")
            .forEach { link ->
                val streamPage = normalizeUrl(link.attr("href"), fixedUrl) ?: return@forEach
                val quality = link.closest(".soraurlx")?.selectFirst("strong")?.text()
                    ?: link.parent()?.selectFirst("strong")?.text()
                    ?: link.text()
                resolveMirrorLink(streamPage, quality.ifBlank { "Download" }, fixedUrl, emitted, subtitleCallback, callback)
            }

        return emitted.isNotEmpty()
    }

    private fun Document.toSearchResults(): List<SearchResponse> {
        return select(".listupd article, .listupd .stylefiv, article.bs, article.stylefiv, .serieslist li")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(
            "a[href][itemprop=url], .bsx > a[href], .thumb > a[href], h2 a[href], h4 a.series[href], a.series[href], a[href]"
        ) ?: if (tagName() == "a") this else return null
        val href = normalizeUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!href.contains(mainUrl) || href.contains("/genres/") || href.contains("/studio/") || href.contains("/season/")) {
            return null
        }

        val rawTitle = listOf(
            anchor.attr("title"),
            selectFirst("h2[itemprop=headline], .tt h2, h4 a.series, .epl-title")?.text(),
            selectFirst("img[title]")?.attr("title"),
            selectFirst("img[alt]")?.attr("alt"),
            anchor.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.cleanTitle() ?: return null

        val poster = selectFirst(".limit img, .thumb img, img")?.imageUrl()?.fixImageQuality()
        val type = getType(selectFirst(".typez, .epx")?.text(), href)
        val episode = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text().ifBlank { anchor.attr("title") })
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return newAnimeSearchResponse(rawTitle, href, type) {
            posterUrl = poster
            episode?.let { addSub(it) }
        }
    }

    private fun Element.toEpisode(): Episode? {
        val href = normalizeUrl(attr("href"), mainUrl) ?: return null
        val label = selectFirst(".epl-title")?.text()?.trim().orEmpty()
            .ifBlank { text().cleanWhitespace() }
        val episodeNumber = selectFirst(".epl-num")?.text()?.toIntOrNull()
            ?: Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(label)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        return newEpisode(href) {
            name = label.ifBlank { episodeNumber?.let { "Episode $it" } }
            episode = episodeNumber
        }
    }

    private fun Document.infoText(label: String): String? {
        return select(".infox .spe span, .single-info .spe span")
            .firstOrNull { span ->
                span.selectFirst("b")?.text()?.cleanLabel()?.equals(label, true) == true
            }
            ?.clone()
            ?.also { it.select("b").remove() }
            ?.text()
            ?.cleanWhitespace()
            ?.ifBlank { null }
    }

    private fun decodeMirrorLinks(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val decodedHtml = runCatching { base64Decode(value.replace("\\s".toRegex(), "")) }.getOrElse { value }
        val links = linkedSetOf<String>()
        val mirrorDoc = Jsoup.parse(decodedHtml)

        mirrorDoc.select("iframe[src], iframe[data-src], source[src], video[src], a[href]").forEach { element ->
            val rawUrl = element.attr("src").ifBlank { element.attr("data-src") }.ifBlank { element.attr("href") }
            normalizeUrl(rawUrl, mainUrl)?.let(links::add)
        }
        Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
            .findAll(decodedHtml)
            .mapNotNull { normalizeUrl(it.value, mainUrl) }
            .forEach(links::add)

        return links.filterNot { it.contains("youtube.", true) || it.contains("youtu.be", true) }
    }

    private suspend fun resolveMirrorLink(
        rawUrl: String,
        label: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val normalized = normalizeUrl(rawUrl, referer) ?: return
        if (!emitted.add(normalized)) return

        if (isDirectMedia(normalized)) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name $label",
                    url = normalized,
                    type = INFER_TYPE,
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(label)
                    this.headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
                }
            )
            return
        }

        if (normalized.contains("filedon.co/", true)) {
            val direct = resolveFiledonDirect(normalized, referer)
            val directUrl = direct?.url?.takeIf { it.isNotBlank() }
            if (directUrl != null) {
                callback(
                    newExtractorLink(
                        source = "Filedon",
                        name = "$name Filedon $label",
                        url = directUrl,
                        type = INFER_TYPE,
                    ) {
                        this.referer = "https://filedon.co/"
                        this.quality = getQualityFromName(label)
                        this.headers = mapOf(
                            "Referer" to "https://filedon.co/",
                            "User-Agent" to USER_AGENT,
                            "Range" to "bytes=0-",
                        )
                    }
                )
                return
            }
        }

        runCatching { loadExtractor(normalized, referer, subtitleCallback, callback) }
    }

    private suspend fun resolveFiledonDirect(url: String, referer: String): FiledonResolved? {
        val fixedUrl = url.replace("/view/", "/embed/")
        val page = runCatching {
            app.get(
                fixedUrl,
                referer = referer,
                headers = mapOf("User-Agent" to USER_AGENT),
            ).document
        }.getOrNull() ?: return null

        val pageJson = page.selectFirst("#app")?.attr("data-page").orEmpty()
        val parsed = tryParseJson<FiledonPage>(pageJson)
        val directUrl = parsed?.props?.url
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.takeIf { it.isNotBlank() }

        return directUrl?.let { FiledonResolved(it, parsed.props.files?.name) }
    }

    private fun pageUrl(pattern: String, page: Int): String {
        val fixed = normalizeUrl(pattern, mainUrl) ?: "$mainUrl/"
        if (page <= 1) return fixed
        val parts = fixed.split("?", limit = 2)
        val base = parts[0].trimEnd('/')
        val query = parts.getOrNull(1)?.let { "?$it" }.orEmpty()
        return "$base/page/$page/$query"
    }

    private fun getType(value: String?, url: String): TvType {
        val text = value.orEmpty()
        return when {
            text.contains("movie", true) || url.contains("type=movie", true) -> TvType.AnimeMovie
            text.contains("ova", true) ||
                text.contains("special", true) ||
                text.contains("ona", true) ||
                text.contains("bd", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun getStatus(value: String?): ShowStatus? {
        return when {
            value?.contains("ongoing", true) == true -> ShowStatus.Ongoing
            value?.contains("completed", true) == true || value?.contains("complete", true) == true -> ShowStatus.Completed
            else -> null
        }
    }

    private fun normalizeUrl(raw: String, baseUrl: String): String? {
        val clean = Jsoup.parse(raw).text()
            .trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .takeIf { it.isNotBlank() && !it.startsWith("javascript:", true) && !it.startsWith("data:", true) }
            ?: return null

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> httpsify(clean)
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()?.let(::httpsify)
        }
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("abs:content"),
            attr("abs:data-src"),
            attr("abs:data-lazy-src"),
            attr("abs:data-litespeed-src"),
            attr("abs:srcset").substringBefore(" "),
            attr("abs:src"),
            attr("content"),
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-litespeed-src"),
            attr("srcset").substringBefore(" "),
            attr("src"),
        ).firstOrNull { it.isNotBlank() }?.let { normalizeUrl(it, mainUrl) }
    }

    private fun Element.iframeUrl(baseUrl: String): String? {
        return listOf(attr("data-litespeed-src"), attr("data-src"), attr("src"))
            .firstOrNull { it.isNotBlank() }
            ?.let { normalizeUrl(it, baseUrl) }
    }

    private fun Element.cleanTextBlock(): String? {
        val content = clone()
        content.select("script, style, iframe, .sharedaddy, .colap").remove()
        return content.select("p").joinToString("\n") { it.text().trim() }
            .ifBlank { content.text().cleanWhitespace() }
            .ifBlank { null }
    }

    private fun isDirectMedia(url: String): Boolean {
        return Regex("""(?i)\.(m3u8|mp4)(?:$|[?#&])""").containsMatchIn(url)
    }

    private fun getQualityFromName(value: String): Int {
        return Regex("""\b(2160|1440|1080|720|480|360|240)\b""", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun String.cleanTitle(): String {
        return Jsoup.parse(this).text()
            .replace(Regex("""(?i)\s+Episode\s+\d+.*$"""), "")
            .replace(Regex("""(?i)\s+(?:Subtitle\s*Indonesia|Sub\s*Indo|English\s*Subbed|Eng\s*Sub)\b.*$"""), "")
            .replace(Regex("""(?i)\s*[-|]\s*Tensei\s*ID.*$"""), "")
            .replace(Regex("""(?i)\bNonton\s+Anime\s+"""), "")
            .cleanWhitespace()
    }

    private fun String.cleanLabel(): String {
        return replace(":", "").cleanWhitespace()
    }

    private fun String.cleanWhitespace(): String {
        return replace(Regex("""\s+"""), " ").trim()
    }

    private fun String.fixImageQuality(): String {
        return replace(Regex("""-\d+x\d+(?=\.[a-zA-Z]{3,4}(?:$|[?#]))"""), "")
            .replace(Regex("""([?&])resize=\d+,\d+"""), "$1")
            .replace(Regex("""[?&]$"""), "")
    }

    private fun String.extractYear(): Int? {
        return Regex("""(19|20)\d{2}""").find(this)?.value?.toIntOrNull()
    }

    private fun String.toScore(): Double? {
        return Regex("""\d+(?:\.\d+)?""").find(this)?.value?.toDoubleOrNull()
    }

    data class FiledonPage(
        @param:JsonProperty("props") val props: FiledonProps? = null,
    )

    data class FiledonProps(
        @param:JsonProperty("url") val url: String? = null,
        @param:JsonProperty("files") val files: FiledonFile? = null,
    )

    data class FiledonFile(
        @param:JsonProperty("name") val name: String? = null,
    )

    data class FiledonResolved(
        val url: String,
        val fileName: String? = null,
    )
}

class TenseiFiledon : ExtractorApi() {
    override val name = "Filedon"
    override val mainUrl = "https://filedon.co"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = url.replace("/view/", "/embed/")
        val document = app.get(
            fixedUrl,
            referer = referer,
            headers = mapOf("User-Agent" to USER_AGENT),
        ).document
        val pageJson = document.selectFirst("#app")?.attr("data-page").orEmpty()
        val page = tryParseJson<FiledonPage>(pageJson)
        val video = page?.props?.url
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.takeIf { it.isNotBlank() }
            ?: return

        callback(
            newExtractorLink(
                source = name,
                name = page.props.files?.name ?: name,
                url = video,
                type = INFER_TYPE,
            ) {
                this.referer = "$mainUrl/"
                this.headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to USER_AGENT,
                    "Range" to "bytes=0-",
                )
            }
        )
    }

    data class FiledonPage(
        @param:JsonProperty("props") val props: FiledonProps? = null,
    )

    data class FiledonProps(
        @param:JsonProperty("url") val url: String? = null,
        @param:JsonProperty("files") val files: FiledonFile? = null,
    )

    data class FiledonFile(
        @param:JsonProperty("name") val name: String? = null,
    )
}
