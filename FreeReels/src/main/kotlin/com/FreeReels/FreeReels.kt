package com.FreeReels

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FreeReels : MainAPI() {
    override var mainUrl = "https://m.mydramawave.com"
    private val nativeApiUrl = "https://apiv2.free-reels.com/frv2-api"
    private val fallbackApiUrl = "https://api.mydramawave.com/h5-api"

    override var name = "FreeReels"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Anime
    )

    private val secureRandom = SecureRandom()
    private val deviceId = (1..32)
        .map { "0123456789abcdef"[secureRandom.nextInt(16)] }
        .joinToString("")

    private val sessionId = java.util.UUID.randomUUID().toString()

    private val authSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv&"
    private val nativeLoginSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv"
    private val cryptoKey = "2r36789f45q01ae5"

    private var sessionToken: String? = null
    private var sessionSecret: String? = null
    private val sessionLock = Mutex()

    private data class NativeCategory(
        val key: String,
        val name: String,
        val tabKey: String,
        val posIndex: Int,
        val type: TvType = TvType.AsianDrama
    )

    private val nativeCategories = listOf(
        NativeCategory("popular", "Populer", "993", 10000),
        NativeCategory("new", "Terbaru", "995", 10000),
        NativeCategory("dubbing", "Dubbing", "1002", 10000),
        NativeCategory("female", "Untuk Perempuan", "994", 10000),
        NativeCategory("male", "Untuk Laki-Laki", "996", 10000),
        NativeCategory("anime", "Anime", "1005", 10001, TvType.Anime)
    )

    override val mainPage = mainPageOf(
        "popular" to "Populer",
        "new" to "Terbaru",
        "dubbing" to "Dubbing",
        "female" to "Untuk Perempuan",
        "male" to "Untuk Laki-Laki",
        "anime" to "Anime"
    )

    private fun decryptIfNeeded(raw: String): String {
        val text = raw.trim()
        if (text.startsWith("{") || text.startsWith("[")) return text

        return runCatching {
            val decoded = Base64.decode(text, Base64.DEFAULT)
            if (decoded.size <= 16) return@runCatching text

            val iv = decoded.copyOfRange(0, 16)
            val payload = decoded.copyOfRange(16, decoded.size)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(cryptoKey.toByteArray(Charsets.UTF_8), "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

            String(cipher.doFinal(payload), Charsets.UTF_8)
        }.getOrDefault(text)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun getNativeHeaders(isVip: Boolean = false): MutableMap<String, String> {
        val ts = System.currentTimeMillis()
        val signature = md5(authSalt + (sessionSecret ?: ""))

        val headers = mutableMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "OpCountryCode" to "ID",
            "X-AppEngine-Country" to "ID",
            "app-language" to "id",
            "prefer_country" to "ID",
            "locale" to "id-ID",
            "language" to "id-ID",
            "country" to "ID",
            "X-Timezone" to "Asia/Jakarta",
            "timezone" to "7",
            "X-Timezone-offset" to "7",
            "network-type" to "WIFI",
            "screen-width" to "411",
            "screen-height" to "891",
            "is-mainland" to "false",
            "device-memory" to "8.00",
            "device-country" to "ID",
            "device-language" to "id-ID",
            "x-device-model" to "23090RA98G",
            "x-device-manufacturer" to "Xiaomi",
            "x-device-brand" to "Redmi",
            "x-device-product" to "sky",
            "x-device-fingerprint" to "Redmi/sky_global/sky:14/UKQ1.231003.002/V816.0.11.0.UMWMIXM:user/release-keys",
            "session-id" to sessionId,
            "app-name" to "com.freereels.app",
            "app-version" to "2.2.91",
            "device-id" to deviceId,
            "device-version" to "34",
            "device" to "android",
            "Authorization" to "oauth_signature=$signature,oauth_token=${sessionToken ?: "undefined"},ts=$ts"
        )

        if (isVip) {
            headers["internal-user-code"] = "666666"
        }

        return headers
    }

    private suspend fun ensureSession() {
        if (!sessionToken.isNullOrBlank()) return

        sessionLock.withLock {
            if (!sessionToken.isNullOrBlank()) return@withLock

            val loginSig = md5(nativeLoginSalt + deviceId)
            val reqBody = mapOf(
                "device_id" to deviceId,
                "device_name" to "Redmi 23090RA98G",
                "device_sign" to loginSig
            ).toJson().toRequestBody("application/json".toMediaTypeOrNull())

            val res = app.post(
                "$nativeApiUrl/anonymous/login",
                headers = getNativeHeaders(),
                requestBody = reqBody
            ).text

            val authData = tryParseJson<NativeAuthResponse>(res)

            sessionToken = authData?.data?.authKey ?: authData?.data?.token
            sessionSecret = authData?.data?.authSecret.orEmpty()
        }
    }

    private fun extractMovies(dataObj: UniversalFeedData?, dest: MutableList<UniversalItem>) {
        if (dataObj == null) return

        fun extract(itemsList: List<UniversalItem>?) {
            itemsList?.forEach { item ->
                val itemType = item.type.orEmpty()

                if (itemType.contains("banner", true)) return@forEach
                if (itemType.equals("ad", true)) return@forEach

                val title = item.title ?: item.name
                val id = item.id?.toString() ?: item.key ?: item.seriesId?.toString()

                if (!title.isNullOrBlank() && !id.isNullOrBlank()) {
                    dest.add(item)
                }

                extract(item.items)
                extract(item.list)
            }
        }

        extract(dataObj.items)
        extract(dataObj.list)
        extract(dataObj.components)
        extract(dataObj.modules)
    }

    private suspend fun getCategoryPage(
        category: NativeCategory,
        page: Int
    ): Pair<List<UniversalItem>, Boolean> {
        val indexUrl =
            "$nativeApiUrl/homepage/v2/tab/index?tab_key=${category.tabKey}&position_index=${category.posIndex}&rec_trigger=0"

        val res = app.get(indexUrl, headers = getNativeHeaders()).text
        val moduleIndex = tryParseJson<UniversalFeedResponse>(res)?.data
            ?: return emptyList<UniversalItem>() to false

        if (page <= 1) {
            val items = mutableListOf<UniversalItem>()
            extractMovies(moduleIndex, items)

            val hasMore = moduleIndex.pageInfo?.hasMore == true ||
                !moduleIndex.pageInfo?.next.isNullOrBlank()

            return items.distinctBy { it.stableId() } to hasMore
        }

        val recommendModule = moduleIndex.items?.firstOrNull { it.type == "recommend" }
            ?: moduleIndex.list?.firstOrNull { it.type == "recommend" }
            ?: moduleIndex.modules?.firstOrNull { it.type == "recommend" }

        val recommendKey = recommendModule?.moduleKey ?: category.tabKey
        var currentNext = moduleIndex.pageInfo?.next
        var currentData: UniversalFeedData? = null

        for (i in 1 until page) {
            if (currentNext.isNullOrBlank()) break

            val reqBody = mapOf(
                "module_key" to recommendKey,
                "next" to currentNext
            ).toJson().toRequestBody("application/json".toMediaTypeOrNull())

            val feedRes = app.post(
                "$nativeApiUrl/homepage/v2/tab/feed",
                headers = getNativeHeaders(),
                requestBody = reqBody
            ).text

            currentData = tryParseJson<UniversalFeedResponse>(feedRes)?.data
            currentNext = currentData?.pageInfo?.next
        }

        val items = mutableListOf<UniversalItem>()
        extractMovies(currentData, items)

        val hasMore = currentData?.pageInfo?.hasMore == true || !currentNext.isNullOrBlank()
        return items.distinctBy { it.stableId() } to hasMore
    }

    private suspend fun findNativeItemBySeriesKey(seriesKey: String): UniversalItem? {
        for (cat in nativeCategories) {
            val (items, _) = getCategoryPage(cat, 1)
            val match = items.firstOrNull {
                it.key == seriesKey ||
                    it.id?.toString() == seriesKey ||
                    it.seriesId?.toString() == seriesKey
            }

            if (match != null) return match
        }

        return null
    }

    private fun hasPlayableSource(ep: NativeEpisode?): Boolean {
        if (ep == null) return false

        return !ep.externalAudioH264.isNullOrBlank() ||
            !ep.externalAudioH265.isNullOrBlank() ||
            !ep.m3u8Url.isNullOrBlank() ||
            !ep.videoUrl.isNullOrBlank()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureSession()

        val category = nativeCategories.find { it.key == request.data }
            ?: throw ErrorLoadingException("Kategori tidak ditemukan: ${request.data}")

        val (rawItems, hasMore) = getCategoryPage(category, page)

        val items = rawItems.mapNotNull { item ->
            item.toSearchResponse(category.type)
        }.distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = hasMore
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        ensureSession()

        val nextToken = if (page <= 1) {
            ""
        } else {
            "offset=${(page - 1) * 20}&page_size=20"
        }

        val reqBody = mapOf(
            "keyword" to query,
            "next" to nextToken
        ).toJson().toRequestBody("application/json".toMediaTypeOrNull())

        val res = app.post(
            "$nativeApiUrl/search/drama",
            headers = getNativeHeaders(),
            requestBody = reqBody
        ).text

        val searchItems = mutableListOf<UniversalItem>()
        val dataObj = runCatching {
            tryParseJson<UniversalFeedResponse>(res)?.data
        }.getOrNull()

        extractMovies(dataObj, searchItems)

        val hasMore = dataObj?.pageInfo?.hasMore == true ||
            !dataObj?.pageInfo?.next.isNullOrBlank()

        val list = searchItems.mapNotNull { item ->
            item.toSearchResponse(
                if (item.type?.contains("anime", true) == true) TvType.Anime else TvType.AsianDrama
            )
        }.distinctBy { it.url }

        return newSearchResponseList(list, hasNext = hasMore)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun load(url: String): LoadResponse {
        ensureSession()

        val seriesId = url.substringAfterLast("/").substringBefore("?").trim()
        if (seriesId.isBlank()) throw ErrorLoadingException("Series ID kosong.")

        var info: DramaInfo? = null

        runCatching {
            val resRaw = app.get(
                "$nativeApiUrl/drama/info_v2?series_id=$seriesId",
                headers = getNativeHeaders(isVip = true)
            ).text

            info = tryParseJson<NativeDetailResponse>(resRaw)?.data?.info
        }

        if (info?.episodeList.isNullOrEmpty()) {
            runCatching {
                val fallbackRaw = app.get(
                    "$fallbackApiUrl/drama/info?series_id=$seriesId",
                    headers = getNativeHeaders(isVip = true)
                ).text

                val fallbackRes = decryptIfNeeded(fallbackRaw)
                val fallbackInfo = tryParseJson<NativeDetailResponse>(fallbackRes)?.data?.info

                if (fallbackInfo != null && !fallbackInfo.episodeList.isNullOrEmpty()) {
                    info = fallbackInfo
                }
            }
        }

        val nativeItem = if (info?.episodeList.isNullOrEmpty()) {
            findNativeItemBySeriesKey(seriesId)
        } else {
            null
        }

        val mainCover = fixUrlNull(
            info?.cover
                ?: info?.verticalCover
                ?: nativeItem?.cover
                ?: nativeItem?.verticalCover
        )

        val mainTitle = info?.name
            ?: nativeItem?.title
            ?: nativeItem?.name
            ?: "Drama"

        val mainPlot = info?.desc ?: nativeItem?.desc
        val episodes = buildEpisodeList(info, nativeItem, mainTitle, mainCover)

        if (mainTitle == "Drama" && mainCover == null && episodes.isEmpty()) {
            throw ErrorLoadingException("Data drama tidak tersedia dari server.")
        }

        return newTvSeriesLoadResponse(mainTitle, url, TvType.AsianDrama, episodes) {
            this.posterUrl = mainCover
            this.plot = mainPlot
            this.comingSoon = episodes.isEmpty()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<NativeEpisode>(data) ?: return false

        val videoUrl = ep.externalAudioH264
            ?: ep.externalAudioH265
            ?: ep.m3u8Url
            ?: ep.videoUrl

        var found = false

        if (!videoUrl.isNullOrBlank()) {
            val isM3u8 = videoUrl.contains(".m3u8", true)

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = fixUrl(videoUrl),
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.headers = mapOf(
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/"
                    )
                }
            )

            found = true
        }

        ep.subtitleList?.forEach { sub ->
            val subUrl = sub.vtt ?: sub.subtitle
            if (!subUrl.isNullOrBlank()) {
                subtitleCallback.invoke(
                    newSubtitleFile(
                        sub.displayName ?: sub.language ?: "Subtitle",
                        fixUrl(subUrl)
                    )
                )
            }
        }

        return found
    }

    private fun buildEpisodeList(
        info: DramaInfo?,
        nativeItem: UniversalItem?,
        mainTitle: String,
        mainCover: String?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        info?.episodeList?.forEachIndexed { index, ep ->
            episodes.add(
                newEpisode(ep.toJson()) {
                    this.name = ep.name ?: "Episode ${ep.index ?: index + 1}"
                    this.episode = ep.index ?: index + 1
                    this.posterUrl = fixUrlNull(ep.cover) ?: mainCover
                }
            )
        }

        if (episodes.isEmpty() && hasPlayableSource(nativeItem?.episodeInfo)) {
            val ep = nativeItem?.episodeInfo ?: return episodes

            episodes.add(
                newEpisode(ep.toJson()) {
                    this.name = ep.name ?: mainTitle
                    this.episode = ep.index ?: 1
                    this.posterUrl = fixUrlNull(ep.cover) ?: mainCover
                }
            )
        }

        return episodes.distinctBy { it.data }
    }

    private fun UniversalItem.stableId(): String {
        return id?.toString()
            ?: key
            ?: seriesId?.toString()
            ?: title
            ?: name
            ?: hashCode().toString()
    }

    private fun UniversalItem.toSearchResponse(type: TvType): SearchResponse? {
        val title = title ?: name ?: return null
        val idStr = id?.toString() ?: key ?: seriesId?.toString() ?: return null

        if (title.equals("Ranking", true)) return null
        if (title.equals("Peringkat", true)) return null
        if (title.equals("Top", true)) return null
        if (this.type?.contains("banner", true) == true) return null

        val isDubbed = title.contains("Dubbed", true) ||
            title.contains("Sulih Suara", true) ||
            title.contains("(Dub)", true)

        return newAnimeSearchResponse(title, idStr, type) {
            this.posterUrl = fixUrlNull(cover ?: verticalCover)
        }.apply {
            if (isDubbed) addDubStatus(DubStatus.Dubbed)
        }
    }
}

// ==========================================
// DATA MODELS PURE NATIVE
// ==========================================

data class NativeAuthResponse(
    @JsonProperty("data") val data: AuthData?
)

data class AuthData(
    @JsonProperty("auth_key") val authKey: String?,
    @JsonProperty("auth_secret") val authSecret: String?,
    @JsonProperty("token") val token: String?
)

data class UniversalFeedResponse(
    @JsonProperty("data") val data: UniversalFeedData?
)

data class UniversalFeedData(
    @JsonProperty("items") val items: List<UniversalItem>?,
    @JsonProperty("list") val list: List<UniversalItem>?,
    @JsonProperty("components") val components: List<UniversalItem>?,
    @JsonProperty("modules") val modules: List<UniversalItem>?,
    @JsonProperty("page_info") val pageInfo: PageInfo?
)

data class UniversalItem(
    @JsonProperty("id") val id: Any?,
    @JsonProperty("key") val key: String?,
    @JsonProperty("series_id") val seriesId: Any?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("desc") val desc: String?,
    @JsonProperty("cover") val cover: String?,
    @JsonProperty("vertical_cover") val verticalCover: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("module_key") val moduleKey: String?,
    @JsonProperty("episode_info") val episodeInfo: NativeEpisode?,
    @JsonProperty("items") val items: List<UniversalItem>?,
    @JsonProperty("list") val list: List<UniversalItem>?
)

data class PageInfo(
    @JsonProperty("has_more") val hasMore: Boolean?,
    @JsonProperty("next") val next: String?
)

data class NativeDetailResponse(
    @JsonProperty("data") val data: DramaInfoData?
)

data class DramaInfoData(
    @JsonProperty("info") val info: DramaInfo?
)

data class DramaInfo(
    @JsonProperty("name") val name: String?,
    @JsonProperty("cover") val cover: String?,
    @JsonProperty("vertical_cover") val verticalCover: String?,
    @JsonProperty("desc") val desc: String?,
    @JsonProperty("episode_list") val episodeList: List<NativeEpisode>?
)

data class NativeEpisode(
    @JsonProperty("index") val index: Int?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("cover") val cover: String?,
    @JsonProperty("external_audio_h264_m3u8") val externalAudioH264: String?,
    @JsonProperty("external_audio_h265_m3u8") val externalAudioH265: String?,
    @JsonProperty("m3u8_url") val m3u8Url: String?,
    @JsonProperty("video_url") val videoUrl: String?,
    @JsonProperty("subtitle_list") val subtitleList: List<NativeSubtitle>?
)

data class NativeSubtitle(
    @JsonProperty("language") val language: String?,
    @JsonProperty("subtitle") val subtitle: String?,
    @JsonProperty("vtt") val vtt: String?,
    @JsonProperty("display_name") val displayName: String?
)