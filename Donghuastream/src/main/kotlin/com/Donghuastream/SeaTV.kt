package com.Donghuastream

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

open class SeaTV : Donghuastream() {

    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://seatv-24.xyz"
    override var name = "SeaTV🍰"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?status=&type=&order=update&page={page}" to "Update Terbaru",
        "$mainUrl/anime/?status=completed&type=&order=update&page={page}" to "Completed",
        "$mainUrl/anime/?status=ongoing&type=&order=update&page={page}" to "Ongoing",
        "$mainUrl/anime/?status=&type=movie&order=update&page={page}" to "Movie",
        "$mainUrl/anime/?status=&type=special&order=update&page={page}" to "Special",
        "$mainUrl/genres/action/page/{page}/" to "Action",
        "$mainUrl/genres/adventure/page/{page}/" to "Adventure",
        "$mainUrl/genres/fantasy/page/{page}/" to "Fantasy",
        "$mainUrl/genres/martial-arts/page/{page}/" to "Martial Arts",
        "$mainUrl/genres/romance/page/{page}/" to "Romance",
        "$mainUrl/genres/sci-fi/page/{page}/" to "Sci-Fi"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        document.select(".mobius option, option[data-index], select option").forEach { server ->
            val base64 = server.attr("value").takeIf { it.isNotEmpty() } ?: return@forEach
            val doc = runCatching { Jsoup.parse(base64Decode(base64)) }.getOrNull() ?: return@forEach

            val iframeUrl = doc.selectFirst("iframe[src]")?.attr("src")?.let(::httpsify)
            val metaUrl = doc.selectFirst("meta[itemprop=embedUrl]")?.attr("content")?.let(::httpsify)
            val url = iframeUrl?.takeIf { it.isNotEmpty() } ?: metaUrl.orEmpty()

            if (url.isNotEmpty()) {
                when {
                    url.contains("vidmoly", true) -> {
                        val link = if (url.contains("=\"")) {
                            "http:" + url.substringAfter("=\"").substringBefore("\"")
                        } else {
                            url
                        }
                        if (loadExtractor(link, referer = data, subtitleCallback, callback)) found = true
                    }
                    url.endsWith(".mp4", true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "All Sub Player",
                                "All Sub Player",
                                url = url,
                                INFER_TYPE
                            ) {
                                this.referer = data
                                this.quality = getQualityFromName(server.text())
                            }
                        )
                        found = true
                    }
                    else -> {
                        if (loadExtractor(url, referer = data, subtitleCallback, callback)) found = true
                    }
                }
            }
        }

        return found
    }
}