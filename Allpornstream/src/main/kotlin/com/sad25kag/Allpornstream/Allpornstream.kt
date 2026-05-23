package com.sad25kag.Allpornstream

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class Allpornstream : MainAPI() {

    override var mainUrl = "https://allpornstream.com"
    override var name = "Allpornstream"

    override val hasMainPage = true
    override val hasQuickSearch = false

    override var lang = "id"

    override val supportedTypes = setOf(TvType.NSFW)

    override val vpnStatus = VPNStatus.MightBeNeeded

    private val appHeaders = mapOf(
        "RSC" to "1",
        "Accept" to "*/*",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/categories/1080-p" to "1080 P",
        "${mainUrl}/categories/60-fps" to "60 FPS",
        "${mainUrl}/categories/amateur" to "Amateur",
        "${mainUrl}/categories/anal" to "Anal",
        "${mainUrl}/categories/asian" to "Asian",
        "${mainUrl}/categories/babe" to "Babe",
        "${mainUrl}/categories/bangbros" to "Bangbros",
        "${mainUrl}/categories/bdsm" to "BDSM",
        "${mainUrl}/categories/big-ass" to "Big Ass",
        "${mainUrl}/categories/big-dick" to "Big Dick",
        "${mainUrl}/categories/big-tits" to "Big Tits",
        "${mainUrl}/categories/bisexual" to "Bisexual",
        "${mainUrl}/categories/blonde" to "Blonde",
        "${mainUrl}/categories/blowjob" to "Blowjob",
        "${mainUrl}/categories/bondage" to "Bondage",
        "${mainUrl}/categories/brazzers" to "Brazzers",
        "${mainUrl}/categories/brunette" to "Brunette",
        "${mainUrl}/categories/casting" to "Casting",
        "${mainUrl}/categories/creampie" to "Creampie",
        "${mainUrl}/categories/cumshot" to "Cumshot",
        "${mainUrl}/categories/deepthroat" to "Deepthroat",
        "${mainUrl}/categories/doggystyle" to "Doggystyle",
        "${mainUrl}/categories/eating-out" to "Eating Out",
        "${mainUrl}/categories/ebony" to "Ebony",
        "${mainUrl}/categories/female-orgasm" to "Female Orgasm",
        "${mainUrl}/categories/fetish" to "Fetish",
        "${mainUrl}/categories/fingering" to "Fingering",
        "${mainUrl}/categories/gangbang" to "Gangbang",
        "${mainUrl}/categories/girl-on-girl" to "Girl On Girl",
        "${mainUrl}/categories/group-sex" to "Group Sex",
        "${mainUrl}/categories/hairy" to "Hairy",
        "${mainUrl}/categories/handjob" to "Handjob",
        "${mainUrl}/categories/interracial" to "Interracial",
        "${mainUrl}/categories/kissing" to "Kissing",
        "${mainUrl}/categories/latina" to "Latina",
        "${mainUrl}/categories/lesbian" to "Lesbian",
        "${mainUrl}/categories/long-hair" to "Long Hair",
        "${mainUrl}/categories/massage" to "Massage",
        "${mainUrl}/categories/masturbation" to "Masturbation",
        "${mainUrl}/categories/milf" to "Milf",
        "${mainUrl}/categories/moaning" to "Moaning",
        "${mainUrl}/categories/natural-breasts" to "Natural Breasts",
        "${mainUrl}/categories/naughtyamerica" to "NaughtyAmerica",
        "${mainUrl}/categories/old-and-young" to "Old And Young",
        "${mainUrl}/categories/onlyfans" to "OnlyFans",
        "${mainUrl}/categories/orgy" to "Orgy",
        "${mainUrl}/categories/orgasm" to "Orgasm",
        "${mainUrl}/categories/outdoor" to "Outdoor",
        "${mainUrl}/categories/passionate" to "Passionate",
        "${mainUrl}/categories/pov" to "POV",
        "${mainUrl}/categories/pussy-licking" to "Pussy Licking",
        "${mainUrl}/categories/redhead" to "Redhead",
        "${mainUrl}/categories/rough" to "Rough",
        "${mainUrl}/categories/russian" to "Russian",
        "${mainUrl}/categories/shaved-pussy" to "Shaved Pussy",
        "${mainUrl}/categories/small-tits" to "Small Tits",
        "${mainUrl}/categories/squirt" to "Squirt",
        "${mainUrl}/categories/stockings" to "Stockings",
        "${mainUrl}/categories/tattoo" to "Tattoo",
        "${mainUrl}/categories/teamskeet" to "Teamskeet",
        "${mainUrl}/categories/teen" to "Teen",
        "${mainUrl}/categories/threesome" to "Threesome",
        "${mainUrl}/categories/undressing" to "Undressing",
        "${mainUrl}/categories/uniforms" to "Uniforms",
        "${mainUrl}/categories/vibrator" to "Vibrator"
    )

    private fun posteriduzenle(url: String): String {
        return if (url.startsWith("http")) {
            val encodedurl = URLEncoder.encode(
                url.replace("\\", ""),
                "utf-8"
            )

            "${mainUrl}/api/images?src=$encodedurl&width=384&quality=60"
        } else {
            fixUrl(url)
        }
    }

    private fun nextiparseet(html: String): List<SearchResponse> {

        val results = mutableListOf<SearchResponse>()

        val blocks = html.split("data-href")

        val titleregex =
            Regex("""data-title["\\:=]+([^"\\]+)""")

        val imageregex =
            Regex("""data-images.*?\[.*?(https?://[^"\\'&;]+)""")

        for (i in 1 until blocks.size) {

            val block = blocks[i]

            val hrefmatch =
                Regex("""^[\\":=\s]*([^"'\\]+)""")
                    .find(block)

            val href =
                hrefmatch?.groupValues?.get(1)
                    ?.trim()
                    ?: continue

            if (!href.startsWith("/post/")) continue

            val title =
                titleregex.find(block)
                    ?.groupValues?.get(1)
                    ?.trim()
                    ?: continue

            val poster =
                imageregex.find(block)
                    ?.groupValues?.get(1)
                    ?: continue

            if (poster.contains("placeholder", true)) continue

            results.add(
                newMovieSearchResponse(
                    title,
                    fixUrl(href),
                    TvType.NSFW
                ) {
                    this.posterUrl = posteriduzenle(poster)
                }
            )
        }

        return results.distinctBy { it.url }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val res = app.get(
            request.data,
            headers = appHeaders
        )

        val results = nextiparseet(res.text)

        return newHomePageResponse(
            request.name,
            results,
            hasNext = false
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val url =
            "${mainUrl}/?search=${
                withContext(Dispatchers.IO) {
                    URLEncoder.encode(query, "utf-8")
                }
            }"

        val res = app.get(
            url,
            headers = appHeaders
        )

        return nextiparseet(res.text)
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> = search(query)

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val res = app.get(
            url,
            headers = appHeaders
        )

        val restext = res.text

        val postdata =
            restext.substringAfter("\"initialPost\":")
                .substringBefore(",\"initialUrls\"")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")

        val title =
            Regex("""video_title":"(.*?)"""")
                .find(postdata)
                ?.groupValues?.get(1)
                ?: return null

        val poster =
            Regex("""image_details":\["(.*?)"""")
                .find(postdata)
                ?.groupValues?.get(1)
                ?.let { posteriduzenle(it) }

        val plot =
            Regex("""description":"(.*?)"""")
                .find(postdata)
                ?.groupValues?.get(1)

        val year =
            Regex("""item_publish_date":"(\d{4})""")
                .find(postdata)
                ?.groupValues?.get(1)
                ?.toIntOrNull()

        val duration =
            Regex("""duration":"(\d+)m""")
                .find(postdata)
                ?.groupValues?.get(1)
                ?.toIntOrNull()

        val alllinks =
            Regex("""embed_url":"(.*?)"""")
                .findAll(postdata)
                .map {
                    it.groupValues[1].replace("\\", "")
                }
                .distinct()
                .joinToString(",")

        val tags =
            Regex("""categories":\[(.*?)]""")
                .find(postdata)
                ?.groupValues?.get(1)
                ?.split(",")
                ?.map {
                    it.trim().removeSurrounding("\"")
                }

        val actors =
            Regex("""item_name":"(.*?)"""")
                .findAll(postdata)
                .map {
                    Actor(it.groupValues[1])
                }
                .distinctBy { it.name }
                .toList()

        val recs =
            nextiparseet(restext)
                .filter { it.url != url }

        return newMovieLoadResponse(
            title,
            url,
            TvType.NSFW,
            alllinks.ifBlank { url }
        ) {

            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = duration
            this.recommendations = recs

            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val links =
            if (data.contains("/post/")) {

                val res =
                    app.get(
                        data,
                        headers = appHeaders
                    ).text

                Regex("""embed_url":"(.*?)"""")
                    .findAll(res)
                    .map {
                        it.groupValues[1]
                            .replace("\\", "")
                    }
                    .toList()

            } else {
                data.split(",")
            }

        links.forEach { link ->

            val clean = link.trim()

            if (clean.isNotBlank()) {
                loadExtractor(
                    clean,
                    mainUrl,
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }
}