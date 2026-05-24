package com.sad25kag.Dramabox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
// NOTE: Do NOT import AppUtils.parsedSafe here — it is not used in this file,
// and the function may not exist in all CloudStream3 versions (causes build error).
// Use app.get(url).text + tryParseJson<T>() instead whenever you need safe JSON parsing.
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// ============================================
// REGION 1: MASTER LINK GENERATOR
// ============================================

object MasterLinkGenerator {
    suspend fun createLink(
        source: String,
        url: String,
        referer: String?,
        quality: Int? = null,
        headers: Map<String, String>? = null
    ): ExtractorLink? {
        val detectedQuality = quality ?: detectQualityFromUrl(url)
        return newExtractorLink(
            source = source,
            name = source,
            url = url,
            type = INFER_TYPE
        ) {
            this.quality = detectedQuality
            if (referer != null) this.referer = referer
            this.headers = headers ?: emptyMap()
        }
    }

    // FIX #3: Default return changed from hardcoded 480 to Qualities.Unknown.value.
    // If quality cannot be detected from the URL, the player should decide — not us.
    fun detectQualityFromUrl(url: String): Int {
        val urlLower = url.lowercase()
        return when {
            urlLower.contains("2160") || urlLower.contains("4k") -> Qualities.P2160.value
            urlLower.contains("1080") -> Qualities.P1080.value
            urlLower.contains("720") -> Qualities.P720.value
            urlLower.contains("480") -> Qualities.P480.value
            urlLower.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}

// ============================================
// REGION 2: LOAD EXTRACTOR WITH FALLBACK
// ============================================

suspend fun loadExtractorWithFallback(
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var deliveredLinks = 0
    val trackedCallback: (ExtractorLink) -> Unit = { link ->
        deliveredLinks++
        callback(link)
    }

    try {
        if (loadExtractor(url, referer, subtitleCallback, trackedCallback)) return true
    } catch (e: Exception) {
        logError("DramaboxEkstraktors", "loadExtractor failed for url=$url", e)
    }

    // Step 2: Try local extractors (Dramabox currently has none)
    val urlDomain = url.removePrefix("http://").removePrefix("https://")
        .split("/").first().lowercase()
    val matchingExtractors = DramaboxEkstraktors.list.filter { extractor ->
        urlDomain.contains(
            extractor.mainUrl.removePrefix("http://").removePrefix("https://")
                .split("/").first().lowercase()
        )
    }

    if (matchingExtractors.isEmpty()) return deliveredLinks > 0

    coroutineScope {
        val semaphore = Semaphore(3)
        matchingExtractors.forEach { extractor ->
            launch {
                semaphore.withPermit {
                    try {
                        extractor.getUrl(url, referer, subtitleCallback, trackedCallback)
                    } catch (e: Exception) {
                        logError("DramaboxEkstraktors", "Extractor ${extractor.name} failed for url=$url", e)
                    }
                }
            }
        }
    }
    return deliveredLinks > 0
}

// ============================================
// REGION 3: EXTRACTORS LIST
// ============================================

object DramaboxEkstraktors {
    val list = listOf<ExtractorApi>()
}
