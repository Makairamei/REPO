package com.byayzen

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class Allpornstream : MainAPI() {
    override var mainUrl = "https://allpornstream.com/"
    override var name = "Allpornstream"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val appHeaders = mapOf(
        "RSC" to "1",
        "Accept" to "*/*",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/categories/1080-p" to "1080 P",
        "${mainUrl}/categories/4k-porn" to "4 K Porn",
        "${mainUrl}/categories/60-fps" to "60 Fps",
        "${mainUrl}/categories/amateur" to "Amateur",
        "${mainUrl}/categories/anal" to "Anal",
        "${mainUrl}/categories/asian" to "Asian",
        "${mainUrl}/categories/babe" to "Babe",
        "${mainUrl}/categories/bangbros" to "Bangbros",
        "${mainUrl}/categories/bdsm" to "Bdsm",
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
        "${mainUrl}/categories/hairy" to "Hair (Hairy)",
        "${mainUrl}/categories/handjob" to "Handjob",
        "${mainUrl}/categories/interracial" to "Interracial",
        "${mainUrl}/categories/kissing" to "Kissing",
        "${mainUrl}/categories/latina" to "Latina",
        "${mainUrl}/categories/lesbian" to "Lesbian / Lesbians",
        "${mainUrl}/categories/long-hair" to "Long Hair",
        "${mainUrl}/categories/massage" to "Massage",
        "${mainUrl}/categories/masturbation" to "Masturbation",
        "${mainUrl}/categories/milf" to "Milf",
        "${mainUrl}/categories/moaning" to "Moaning",
        "${mainUrl}/categories/natural-breasts" to "Natural Breasts",
        "${mainUrl}/categories/naughtyamerica" to "Naughtyamerica",
        "${mainUrl}/categories/old-and-young" to "Old And Young",
        "${mainUrl}/categories/onlyfans" to "Onlyfans",
        "${mainUrl}/categories/orgy" to "Orgy",
        "${mainUrl}/categories/orgasm" to "Orgasm",
        "${mainUrl}/categories/outdoor" to "Outdoor",
        "${mainUrl}/categories/passionate" to "Passionate",
        "${mainUrl}/categories/pov" to "Pov",
        "${mainUrl}/categories/pussy-licking" to "Pussy Licking",
        "${mainUrl}/categories/redhead" to "Redhead",
        "${mainUrl}/categories/rough" to "Rough",
        "${mainUrl}/categories/russian" to "Russian",
        "${mainUrl}/categories/shaved-pussy" to "Shaved Pussy",
        "${mainUrl}/categories/small-tits" to "Small Tits",
        "${mainUrl}/categories/squirt" to "Squirt",
        "${mainUrl}/categories/stockings" to "Stockings",
        "${mainUrl}/categories/tattoo" to "Tattoo / Tattooed",
        "${mainUrl}/categories/teamskeet" to "Teamskeet",
        "${mainUrl}/categories/teen" to "Teen",
        "${mainUrl}/categories/threesome" to "Threesome",
        "${mainUrl}/categories/undressing" to "Undressing",
        "${mainUrl}/categories/uniforms" to "Uniforms",
        "${mainUrl}/categories/vibrator" to "Vibrator"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val res = app.get(request.data, headers = appHeaders).text
        val elements = Regex("""<div class="thumb-container">(.*?)</div>""").findAll(res)
        
        val list = elements.map {
            val html = it.groupValues[1]
            val title = Regex("""title="(.*?)"""").find(html)?.groupValues?.get(1) ?: ""
            val href = Regex("""href="(.*?)"""").find(html)?.groupValues?.get(1) ?: ""
            val poster = Regex("""src="(.*?)"""").find(html)?.groupValues?.get(1) ?: ""
            newMovieSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }.toList()

        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${mainUrl}/search/${URLEncoder.encode(query, "UTF-8")}"
        val res = app.get(url, headers = appHeaders).text
        val elements = Regex("""<div class="thumb-container">(.*?)</div>""").findAll(res)

        return elements.map {
            val html = it.groupValues[1]
            val title = Regex("""title="(.*?)"""").find(html)?.groupValues?.get(1) ?: ""
            val href = Regex("""href="(.*?)"""").find(html)?.groupValues?.get(1) ?: ""
            val poster = Regex("""src="(.*?)"""").find(html)?.groupValues?.get(1) ?: ""
            newMovieSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = appHeaders).text
        val title = Regex("""<h1>(.*?)</h1>""").find(res)?.groupValues?.get(1) ?: ""
        val poster = Regex("""poster="(.*?)"""").find(res)?.groupValues?.get(1) ?: ""
        val plot = Regex("""<p>(.*?)</p>""").find(res)?.groupValues?.get(1) ?: ""
        val duration = Regex("""duration="(.*?)"""").find(res)?.groupValues?.get(1) ?: ""
        
        // Perbaikan: Pastikan tahun dikonversi dengan aman ke Int
        val yearString = Regex("""year="(.*?)"""").find(res)?.groupValues?.get(1)
        val year: Int = yearString?.toIntOrNull() ?: 0

        val tags = Regex("""categories":\[(.*?)]""").find(res)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim().removeSurrounding("\"") }

        val recs = Regex("""<div class="thumb-container">(.*?)</div>""").findAll(res)
            .map {
                val html = it.groupValues[1]
                val t = Regex("""title="(.*?)"""").find(html)?.groupValues?.get(1) ?: ""
                val h = Regex("""href="(.*?)"""").find(html)?.groupValues?.get(1) ?: ""
                val p = Regex("""src="(.*?)"""").find(html)?.groupValues?.get(1) ?: ""
                newMovieSearchResponse(t, h) { this.posterUrl = p }
            }.toList()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = duration
            this.recommendations = recs
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = appHeaders).text
        val links = Regex("""source src="(.*?)"""").findAll(res)
            .map { it.groupValues[1] }
            .toList()

        links.forEach { link ->
            // Perbaikan: Menggunakan constructor ExtractorLink standar
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    link,
                    this.mainUrl,
                    Qualities.Unknown.value,
                    false
                )
            )
        }
        return true
    }
}
