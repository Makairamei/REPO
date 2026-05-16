package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    private val mainApi = "https://api.gofile.io"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)").find(url)?.groupValues?.get(1)
        val token = app.get("$mainApi/createAccount").parsedSafe<Account>()?.data?.get("token")
        val websiteToken = app.get("$mainUrl/dist/js/alljs.js").text.let {
            Regex("fetchData.wt\\s*=\\s*\"([^\"]+)").find(it)?.groupValues?.get(1)
        }
        app.get("$mainApi/getContent?contentId=$id&token=$token&wt=$websiteToken")
            .parsedSafe<Source>()?.data?.contents?.forEach {
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        it.value["link"] ?: return,
                    ) {
                        // this.quality = getQuality(it.value["name"])
                        this.headers = mapOf(
                            "Cookie" to "accountToken=$token"
                        )
                    }
                )
            }

    }

    private fun getQuality(str: String?): Int {
        return Regex("(?<!\\d)(\\d{3,4})(?=p)", RegexOption.IGNORE_CASE).find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    data class Account(
        @JsonProperty("data") val data: HashMap<String, String>? = null,
    )

    data class Data(
        @JsonProperty("contents") val contents: HashMap<String, HashMap<String, String>>? = null,
    )

    data class Source(
        @JsonProperty("data") val data: Data? = null,
    )
}

class Acefile : ExtractorApi() {
    override val name = "Acefile"
    override val mainUrl = "https://acefile.co"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = "/(?:d|download|player|f|file)/(\\w+)".toRegex().find(url)?.groupValues?.get(1)
        val script = getAndUnpack(app.get("$mainUrl/player/${id ?: return}").text)
        val service = """service\s*=\s*['"]([^'"]+)""".toRegex().find(script)?.groupValues?.get(1)
        val serverUrl = """['"](\S+check&id\S+?)['"]""".toRegex().find(script)?.groupValues?.get(1)
            ?.replace("\"+service+\"", service ?: return)

        val video = app.get(serverUrl ?: return, referer = "$mainUrl/").parsedSafe<Source>()?.data

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                video ?: return
            ) 
        )

    }

    data class Source(
        val data: String? = null,
    )
}

class Archivd : ExtractorApi() {
    override val name: String = "Archivd"
    override val mainUrl: String = "https://archivd.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url).document
        val json = res.select("div#app").attr("data-page")
        val video = AppUtils.tryParseJson<Sources>(json)?.props?.datas?.data?.link?.media
        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                video ?: return,
                INFER_TYPE
            ) {
                this.referer = "$mainUrl/"
            }
        )
    }

    data class Link(
        @JsonProperty("media") val media: String? = null,
    )

    data class Data(
        @JsonProperty("link") val link: Link? = null,
    )

    data class Datas(
        @JsonProperty("data") val data: Data? = null,
    )

    data class Props(
        @JsonProperty("datas") val datas: Datas? = null,
    )

    data class Sources(
        @JsonProperty("props") val props: Props? = null,
    )
}

class Krakenfiles : ExtractorApi() {
    override val name = "Krakenfiles"
    override val mainUrl = "https://krakenfiles.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("/(?:view|embed-video)/([\\da-zA-Z]+)").find(url)?.groupValues?.get(1)
        val doc = app.get("$mainUrl/embed-video/$id").document
        val link = doc.selectFirst("source")?.attr("src")

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                httpsify(link ?: return),
            )
        )
    }
}