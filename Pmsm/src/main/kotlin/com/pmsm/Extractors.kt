package com.pmsm

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

class DhtprePmsm : VidhideExtractor() {
    override var mainUrl = "https://dhtpre.com"
}

class NetuPmsm : VidhideExtractor() {
    override var mainUrl = "https://netu.msmbot.club"
}

class Playerxupns : VidStack() {
    override var name = "Playerxupns"
    override var mainUrl = "https://playerx.upns.live"
    override var requiresReferer = true
}

class Playerxp2p : VidStack() {
    override var name = "Playerxp2p"
    override var mainUrl = "https://playerx.p2pstream.online"
    override var requiresReferer = true
}

class Playerxseek : VidStack() {
    override var name = "Playerxseek"
    override var mainUrl = "https://playerx.seekplays.online"
    override var requiresReferer = true
}

class Larhu : ExtractorApi() {
    override var name = "Larhu"
    override var mainUrl = "https://larhu.website"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).text

        val streamurl = Regex("""file\s*:\s*"([^"]+\.(m3u8|mp4))"""")
            .find(doc)
            ?.groupValues?.get(1)
            ?: return

        if (streamurl.contains(".m3u8", true)) {
            M3u8Helper.generateM3u8(name, url, url).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamurl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = streamurl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

class Playerxrpms : VidStack() {
    override var name = "Playerxrpms"
    override var mainUrl = "https://playerx.rpmstream.online"
    override var requiresReferer = true
}

class Player4me : VidStack() {
    override var name = "Player4me"
    override var mainUrl = "https://playerx.player4me.online"
    override var requiresReferer = true
}

class Ezplayer : VidStack() {
    override var name = "Ezplayer"
    override var mainUrl = "https://playerx.ezplayer.stream"
    override var requiresReferer = true
}

class YandexcdnPmsm : VidhideExtractor() {
    override var mainUrl = "https://yandexcdn.com"
}
