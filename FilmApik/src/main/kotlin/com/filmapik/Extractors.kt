package com.sad25kag.filmapik

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink

class Filmapikstrp2p : VidStack() {
    override var name = "Filmapikstrp2p"
    override var mainUrl = "https://fiilmapik.strp2p.site"
    override var requiresReferer = true
}

class BuzzServer : ExtractorApi() {
    override val name = "BuzzServer"
    override val mainUrl = "https://buzzheavier.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleanUrl = url.trim().trimEnd('/')

            val qualityText = app.get(
                cleanUrl,
                referer = referer ?: mainUrl
            ).documentLarge.selectFirst(
                "div.max-w-2xl > span, span:matchesOwn((?i)\\d{3,4}p)"
            )?.text()

            val quality = qualityText
                ?.replace(Regex("\\D"), "")
                ?.toIntOrNull()
                ?: 0

            val response = app.get(
                "$cleanUrl/download",
                referer = cleanUrl,
                allowRedirects = false
            )

            val redirectUrl = response.headers["hx-redirect"]
                ?: response.headers["location"]
                ?: ""

            if (redirectUrl.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        redirectUrl
                    ) {
                        this.quality = quality
                    }
                )
            } else {
                Log.w(name, "No redirect URL found in headers.")
            }
        } catch (e: Exception) {
            Log.e(name, "Exception occurred: ${e.message}")
        }
    }
}