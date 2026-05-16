package com.istarvin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LuluVid : StreamWishExtractor() {
    override val name = "LuluStream"
    override val mainUrl = "https://luluvid.com"
}

class Vidara : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.so"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (id.isBlank()) return

        val res = app.post("https://vidara.so/api/stream", json = mapOf("filecode" to id))
            .parsedSafe<Result>() ?: return

        generateM3u8(name, res.url, mainUrl)
            .forEach(callback)
    }

    data class Result(
        @JsonProperty("streaming_url") val url: String
    )
}

class RubyVidHub : ExtractorApi() {
    override var name = "RubyVidHub"
    override var mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = false

    private val m3u8Regex = Regex("""[:=]\s*"([^"\s]+(\.m3u8|master\.txt)[^"\s]*)""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (id.isBlank()) return

        val text = app.post(
            "$mainUrl/dl",
            data = mapOf("file_code" to id, "op" to "embed")
        ).text

        val res = getAndUnpack(text)

        m3u8Regex.findAll(res).forEach { match ->
            generateM3u8(
                source = name,
                streamUrl = match.groupValues[1],
                referer = mainUrl
            ).forEach(callback)
        }
    }
}

class SubtitleCat : ExtractorApi() {
    override val name = "SubtitleCat"
    override val mainUrl = "https://subtitlecat.com"
    override val requiresReferer = false

    private fun String.normalize(): String {
        return this.filter { c -> c.isLetterOrDigit() }.lowercase()
    }

    private val codeRegex = Regex("""[a-zA-Z]+-\d+""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val query = url.substringAfter("query=").let { codeRegex.find(it)?.value } ?: return
        val queryUrl = "${mainUrl}/index.php?search=$query"
        val doc = app.get(queryUrl).document
        val subs = doc.select(".sub-table a")
            .map { mainUrl + '/' + it.attr("href") }
            .take(3)
            .filter {
                it.normalize().contains(query.normalize())
            }
            .ifEmpty { return }

        CoroutineScope(Dispatchers.IO).launch {
            subs.forEach { subUrl ->
                launch {
                    val subPageDoc = app.get(subUrl).document
                    val href =
                        subPageDoc.getElementById("download_en")?.attr("href") ?: return@launch

                    subtitleCallback(newSubtitleFile("English", mainUrl + href))
                }
            }
        }
    }
}

open class DoodStream : ExtractorApi() {
    override var name = "DoodStream"
    override var mainUrl = "https://myvidplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl =
            url.replace("doply.net", "myvidplay.com").replace("vide0.net", "myvidplay.com")
        Log.d("STB_Dood", "url = $url")
        val response = app.get(
            embedUrl,
            referer = mainUrl,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0")
        ).text

        val md5Regex = Regex("/pass_md5/([^/]*)/([^/']*)")
        val md5Match = md5Regex.find(response)
        val md5Path = md5Match?.value.toString()
        val expiry = md5Match?.groupValues?.getOrNull(1) ?: ""
        val token = md5Match?.groupValues?.getOrNull(2) ?: ""
        val md5Url = mainUrl + md5Path

        val md5Response = app.get(
            md5Url,
            referer = embedUrl,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0")
        ).text

        val baseLink = md5Response.trim()
        val directLink = if (token.isNotEmpty() && expiry.isNotEmpty()) {
            "$baseLink?token=$token&expiry=${expiry}000"
        } else {
            baseLink
        }

        callback.invoke(
            newExtractorLink(
                source = this.name, name = this.name, url = directLink, type = INFER_TYPE
            ) {
                this.referer = "https://myvidplay.com"
                this.quality = Qualities.Unknown.value
                this.headers =
                    mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0")
            })
    }
}

class DoodDoply : DoodStream() {
    override var mainUrl = "https://doply.net"
    override var name = "DoodDoply"
}

class DoodVideo : DoodStream() {
    override var mainUrl = "https://vide0.net"
    override var name = "DoodVideo"
}

class Ds2Play : DoodStream() {
    override var mainUrl = "https://ds2play.com"
}

class D000d : DoodStream() {
    override var mainUrl = "https://d000d.com"
}

class Streamhihi : StreamWishExtractor() {
    override var name = "Streamhihi"
    override var mainUrl = "https://streamhihi.com"
}

class Javsw : StreamWishExtractor() {
    override var mainUrl = "https://javsw.me"
    override var name = "Javsw"
}

class VidhideVIP : VidHidePro() {
    override var mainUrl = "https://vidhidevip.com"
    override var name = "VidhideVIP"
}

class Javlion : VidHidePro() {
    override var mainUrl = "https://javlion.xyz"
    override var name = "Javlion"
}

class VidHidePro1 : VidHidePro() {
    override var mainUrl = "https://filelions.live"
}

class VidHidePro2 : VidHidePro() {
    override var mainUrl = "https://filelions.online"
}

class VidHidePro3 : VidHidePro() {
    override var mainUrl = "https://filelions.to"
}

class VidHidePro4 : VidHidePro() {
    override var mainUrl = "https://kinoger.be"
}

class VidHidePro6 : VidHidePro() {
    override var mainUrl = "https://vidhidepre.com"
}

class VidHidePro7 : VidHidePro() {
    override var mainUrl = "https://vidhidehub.com"
}

class Dhcplay : VidHidePro() {
    override var name = "DHC Play"
    override var mainUrl = "https://dhcplay.com"
}

class Smoothpre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

class Dhtpre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://dhtpre.com"
}

class Peytonepre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://peytonepre.com"
}

class Movearnpre : ExtractorApi() {
    override var name = "EarnVids"
    override var mainUrl = "https://movearnpre.com"
    override val requiresReferer = false

    private val m3u8Regex = Regex("""[:=]\s*"([^"\s]+(\.m3u8)[^"\s]*)""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val text = app.get(url).text

        val script = getAndUnpack(text)

        m3u8Regex.findAll(script).forEach { m3u8Match ->
            val url = fixUrl(m3u8Match.groupValues[1])
            if (url.contains("?")) return@forEach
            generateM3u8(
                name,
                url,
                referer = "$mainUrl/",
            ).forEach(callback)
        }
    }
}

class JavVids : VidHidePro() {
    override var name = "JavVids"
    override var mainUrl = "https://jav-vids.xyz"
}

class Dintezuvio : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://dintezuvio.com"
}

class Hanerix : ExtractorApi() {
    override var name = "HGLink"
    override var mainUrl = "https://hanerix.com"
    override val requiresReferer = false

    private val m3u8Regex = Regex("""[:=]\s*"([^"\s]+(\.m3u8)[^"\s]*)""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val text = app.get(url).text

        val script = getAndUnpack(text)

        m3u8Regex.findAll(script).forEach { m3u8Match ->
            val url = fixUrl(m3u8Match.groupValues[1])
            if (!url.contains("?")) return@forEach
            generateM3u8(
                name,
                url,
                referer = "$mainUrl/",
            ).forEach(callback)
        }
    }
}

class HgLink : ExtractorApi() {
    override var name = "HGLink"
    override var mainUrl = "https://hglink.to"
    override val requiresReferer = false

    private val redirectUrl = "https://hanerix.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (id.isBlank()) return

        val newUrl = "$redirectUrl/e/$id"

        loadExtractor(newUrl, referer, subtitleCallback, callback)
    }
}

class RyderJet : VidHidePro() {
    override var name = "RyderJet"
    override var mainUrl = "https://ryderjet.com"
}

class MyCloudZ : VidHidePro() {
    override var mainUrl = "https://mycloudz.cc"
    override var name = "MyCloudZ"
}

class Turboplayers : ExtractorApi() {
    override var mainUrl = "https://turboplayers.xyz"
    override var name = "TurboPlayer"
    override val requiresReferer = false

    private val urlRegex = Regex("""var urlPlay = '(.*)';""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val text = app.get(url).text

        urlRegex.find(text)?.groupValues?.get(1)?.let {
            if (it.contains("m3u8")) {
                generateM3u8(name, it, mainUrl).forEach(callback)
            } else {
                callback(
                    newExtractorLink(name, name, it)
                )
            }
        }
    }
}

class LulusStream : ExtractorApi() {
    override var name = "LuluStream"
    override var mainUrl = "https://luluvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val filecode = url.substringAfterLast("/")
        val post = app.post(
            "$mainUrl/dl",
            data = mapOf(
                "op" to "embed",
                "file_code" to filecode,
                "auto" to "1",
                "referer" to (referer ?: "")
            )
        ).document
        post.selectFirst("script:containsData(vplayer)")?.data()?.let { script ->
            Regex("file:\"(.*)\"").find(script)?.groupValues?.get(1)?.let { link ->
                callback(newExtractorLink(name, name, link) {
                    this.referer = mainUrl
                    this.quality = Qualities.P1080.value
                })
            }
        }
    }
}

class Javclan : ExtractorApi() {
    override var name = "Javclan"
    override var mainUrl = "https://javclan.com"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val res = app.get(url, referer = referer)
        val script = res.document.selectFirst("script:containsData(sources)")?.data().toString()
        Regex("file:\"(.*?)\"").find(script)?.groupValues?.get(1)?.let { link ->
            return listOf(newExtractorLink(name, name, link, INFER_TYPE) {
                this.referer = referer ?: ""
            })
        }
        return null
    }
}

class Javggvideo : ExtractorApi() {
    override var name = "Javgg Video"
    override var mainUrl = "https://javggvideo.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url).text
        val link = response.substringAfter("var urlPlay = '").substringBefore("';")
        if (link.contains("m3u8")) {
            generateM3u8(name, link, mainUrl).forEach(callback)
        } else {
            callback.invoke(
                newExtractorLink(name, name, link, INFER_TYPE) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }

    }
}

class Swhoi : Filesim() {
    override var mainUrl = "https://swhoi.com"
    override var name = "Streamwish"
}

class MixDropis : MixDrop() {
    override var mainUrl = "https://mixdrop.is"
}

class Javmoon : Filesim() {
    override var mainUrl = "https://javmoon.me"
    override var name = "FileMoon"
}


class StbP2P : VidStack() {
    override var mainUrl = "https://stb.strp2p.com"
    override var name = "STBP2P"
}

class Playerupnone : VidStack() {
    override var mainUrl = "https://player.upn.one"
    override var name = "UPNP2P"
}

open class Turtleviplay : ExtractorApi() {
    override var name = "Turtleviplay"
    override var mainUrl = "https://turtleviplay.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).document
        val m3u8 = res.selectFirst("#video_player")?.attr("data-hash") ?: return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Origin" to "https://turtleviplay.xyz",
                    "Accept" to "*/*",
                )
            }
        )
    }
}

class Turboviplay : Turtleviplay() {
    override var name = "Turboviplay"
    override var mainUrl = "https://turboviplay.com"
}

class Reely : ExtractorApi() {
    override val name = "Reely"
    override val mainUrl = "https://embed.reely.live"
    override val requiresReferer = true

    private val reelxiaProxy = "https://reelxia-proxy.istarvin.workers.dev"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.substringAfter("v=").substringBefore("&")

        generateM3u8(name, "$reelxiaProxy/$videoId/2", mainUrl).forEach(callback)
        subtitleCallback(newSubtitleFile("English", "$reelxiaProxy/$videoId/subtitle/en"))
    }
}