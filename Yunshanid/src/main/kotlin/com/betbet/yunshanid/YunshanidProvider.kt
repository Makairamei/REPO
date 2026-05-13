package com.betbet.yunshanid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class YunshanidProvider : MainAPI() {

    override var mainUrl = "https://yunshanid.site"
    override var name = "Yunshanid"
    override val hasMainPage = true
    override var lang = "id"

    override var sequentialMainPage = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Movie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/ongoing/" to "Ongoing",
        "$mainUrl/completed/" to "Completed",
        "$mainUrl/category/movie/" to "Movie"
    )

    // =========================
    // SAFE SELECTOR
    // =========================

    private fun Element.safeText(
        vararg queries: String
    ): String? {

        queries.forEach {

            val text =
                selectFirst(it)
                    ?.text()
                    ?.trim()

            if (!text.isNullOrBlank())
                return text
        }

        return null
    }

    private fun Element.safeAttr(
        attr: String,
        vararg queries: String
    ): String? {

        queries.forEach {

            val value =
                selectFirst(it)
                    ?.attr(attr)

            if (!value.isNullOrBlank())
                return value
        }

        return null
    }

    // =========================
    // SEARCH RESULT
    // =========================

    private fun Element.toSearchResult(): SearchResponse? {

        val title = safeText(
            "h2",
            ".tt",
            ".entry-title"
        ) ?: return null

        val href = safeAttr(
            "href",
            "a"
        ) ?: return null

        val poster = safeAttr(
            "data-src",
            "img"
        ) ?: safeAttr(
            "src",
            "img"
        )

        val type = when {

            title.contains("movie", true)
                    -> TvType.Movie

            title.contains("ova", true)
                    -> TvType.OVA

            title.contains("special", true)
                    -> TvType.OVA

            else -> TvType.Anime
        }

        return newAnimeSearchResponse(
            title,
            href,
            type
        ) {
            this.posterUrl = poster
        }
    }

    // =========================
    // MAIN PAGE
    // =========================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url =
            if (page == 1)
                request.data
            else
                "${request.data}page/$page/"

        val document =
            app.get(url).document

        val home = mutableListOf<SearchResponse>()

        val selectors = listOf(
            "article",
            ".bs",
            ".bsx",
            ".listupd .bs"
        )

        selectors.forEach { selector ->

            document.select(selector)
                .mapNotNullTo(home) {
                    it.toSearchResult()
                }
        }

        return newHomePageResponse(
            request.name,
            home.distinctBy {
                it.url
            }
        )
    }

    // =========================
    // SEARCH
    // =========================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        return try {

            val response = app.get(
                "$mainUrl/wp-json/wp/v2/search?search=$query"
            ).parsedSafe<List<Map<String, Any>>>()

            response?.mapNotNull {

                val title =
                    it["title"]?.toString()
                        ?: return@mapNotNull null

                val url =
                    it["url"]?.toString()
                        ?: return@mapNotNull null

                newAnimeSearchResponse(
                    title,
                    url,
                    TvType.Anime
                )

            } ?: emptyList()

        } catch (_: Exception) {

            val document =
                app.get(
                    "$mainUrl/?s=$query"
                ).document

            document.select(
                "article, .bs, .bsx"
            ).mapNotNull {
                it.toSearchResult()
            }
        }
    }

    // =========================
    // LOAD DETAIL
    // =========================

    override suspend fun load(
        url: String
    ): LoadResponse {

        val document =
            app.get(
                url,
                referer = mainUrl
            ).document

        val title = document.safeText(
            "h1.entry-title",
            ".entry-title",
            "h1"
        ) ?: "No Title"

        val poster = document.safeAttr(
            "src",
            ".thumb img",
            ".infox img",
            "img"
        )

        val description = document.safeText(
            ".entry-content p",
            ".desc",
            ".synp"
        )

        val tags = document.select(
            ".genxed a, .mgen a, .info-content a"
        ).map {
            it.text()
        }

        val recommendations =
            document.select(
                ".bs, .bsx, article"
            ).mapNotNull {
                it.toSearchResult()
            }

        // =========================
        // NORMAL EPISODE
        // =========================

        val normalEpisodes =
            document.select(
                ".eplister li, #chapterlist li, .episodelist li"
            ).mapIndexed { index, ep ->

                val epName =
                    ep.selectFirst("a")
                        ?.text()
                        ?.trim()

                val epUrl =
                    ep.selectFirst("a")
                        ?.attr("href")
                        ?: ""

                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = index + 1
                }
            }

        // =========================
        // AJAX EPISODE
        // =========================

        val ajaxEpisodes =
            mutableListOf<Episode>()

        document.select("script")
            .forEach { script ->

                val data = script.html()

                Regex(
                    """postid.?[:=].?['"](\d+)"""
                ).find(data)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { postId ->

                        try {

                            val ajax =
                                app.post(
                                    "$mainUrl/wp-admin/admin-ajax.php",
                                    data = mapOf(
                                        "action" to "action_select_episode",
                                        "postid" to postId
                                    ),
                                    referer = url
                                ).document

                            ajax.select("option")
                                .forEachIndexed { index, ep ->

                                    val epUrl =
                                        ep.attr("value")

                                    if (epUrl.isNotBlank()) {

                                        ajaxEpisodes.add(
                                            newEpisode(epUrl) {
                                                this.name = ep.text()
                                                this.episode = index + 1
                                            }
                                        )
                                    }
                                }

                        } catch (_: Exception) {
                        }
                    }
            }

        val finalEpisodes =
            when {

                ajaxEpisodes.isNotEmpty()
                        -> ajaxEpisodes

                normalEpisodes.isNotEmpty()
                        -> normalEpisodes

                else -> listOf(
                    newEpisode(url) {
                        this.name = "Full Movie"
                    }
                )
            }

        val type = when {

            title.contains("movie", true)
                    -> TvType.Movie

            title.contains("ova", true)
                    -> TvType.OVA

            title.contains("special", true)
                    -> TvType.OVA

            else -> TvType.Anime
        }

        return newAnimeLoadResponse(
            title,
            url,
            type
        ) {

            posterUrl = poster

            plot = description

            this.tags = tags

            this.recommendations =
                recommendations

            year = Regex("""\d{4}""")
                .find(document.text())
                ?.value
                ?.toIntOrNull()

            addEpisodes(
                DubStatus.Subbed,
                finalEpisodes.reversed()
            )
        }
    }

    // =========================
    // SAFE EXTRACTOR
    // =========================

    private suspend fun extractSafe(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        try {

            loadExtractor(
                url,
                referer,
                subtitleCallback,
                callback
            )

        } catch (_: Exception) {
        }

        try {

            val document: Document =
                app.get(
                    url,
                    referer = referer
                ).document

            document.select("iframe")
                .forEach {

                    val iframe =
                        it.attr("src")

                    if (iframe.isNotBlank()) {

                        try {

                            loadExtractor(
                                iframe,
                                url,
                                subtitleCallback,
                                callback
                            )

                        } catch (_: Exception) {
                        }
                    }
                }

        } catch (_: Exception) {
        }
    }

    // =========================
    // LOAD LINKS
    // =========================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document =
            app.get(
                data,
                referer = mainUrl
            ).document

        val servers =
            mutableListOf<String>()

        // iframe
        document.select("iframe")
            .forEach {

                val src =
                    it.attr("src")

                if (src.isNotBlank())
                    servers.add(src)
            }

        // href button
        document.select(
            "a[href], option"
        ).forEach {

            val href =
                it.attr("href")
                    .ifBlank {
                        it.attr("value")
                    }

            if (
                href.contains("http")
            ) {
                servers.add(href)
            }
        }

        // packed script
        document.select("script")
            .forEach {

                val script =
                    it.html()

                Regex(
                    """https?:\/\/[^"']+"""
                ).findAll(script)
                    .forEach { match ->

                        val urlFound =
                            match.value

                        if (
                            urlFound.contains(
                                "mp4upload",
                                true
                            ) ||
                            urlFound.contains(
                                "dood",
                                true
                            ) ||
                            urlFound.contains(
                                "stream",
                                true
                            ) ||
                            urlFound.contains(
                                "filemoon",
                                true
                            )
                        ) {

                            servers.add(
                                urlFound
                            )
                        }
                    }
            }

        servers.distinct()
            .forEach {

                extractSafe(
                    it,
                    data,
                    subtitleCallback,
                    callback
                )
            }

        return true
    }
}