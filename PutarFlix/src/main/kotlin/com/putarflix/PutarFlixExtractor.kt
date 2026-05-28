package com.putarflix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink

internal object PutarFlixExtractor {
    suspend fun extract(
        candidates: List<String>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        providerName: String,
    ): Boolean {
        var found = false
        candidates.distinct().forEach { videoUrl ->
            if (PutarFlixUtils.looksDirectVideo(videoUrl)) {
                found = true
                callback.invoke(
                    ExtractorLink(
                        source = providerName,
                        name = "$providerName Direct",
                        url = videoUrl,
                        referer = referer,
                        quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                        isM3u8 = videoUrl.contains(".m3u8", true)
                    )
                )
            } else if (PutarFlixUtils.isKnownPlayableHost(videoUrl)) {
                found = true
                com.lagradost.cloudstream3.utils.loadExtractor(videoUrl, referer, subtitleCallback, callback)
            }
        }
        return found
    }
}
