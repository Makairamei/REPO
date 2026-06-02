package sad25kag.internetarchive

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URLEncoder
import kotlin.math.roundToInt

class InternetArchiveProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "Internet Archive"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Others
    )

    companion object {
        private const val PAGE_SIZE = 26
        private const val DATA_SEPARATOR = "||SORT||"

        private fun archiveData(
            query: String,
            sort: String = "downloads desc"
        ): String = query + DATA_SEPARATOR + sort
    }

    override val mainPage = listOf(
        MainPageData(
            "Update Terbaru",
            archiveData("mediatype:(movies) AND collection:(movies)", "date desc")
        ),
        MainPageData(
            "Feature Films",
            archiveData("mediatype:(movies) AND collection:(feature_films)")
        ),
        MainPageData(
            "Short Films",
            archiveData("mediatype:(movies) AND collection:(short_films)")
        ),
        MainPageData(
            "Animation & Cartoons",
            archiveData("mediatype:(movies) AND collection:(animationandcartoons)")
        ),
        MainPageData(
            "Classic TV",
            archiveData("mediatype:(movies) AND collection:(classic_tv)")
        ),
        MainPageData(
            "Sci-Fi & Horror",
            archiveData("mediatype:(movies) AND collection:(scifi_horror)")
        ),
        MainPageData(
            "Community Video",
            archiveData("mediatype:(movies) AND collection:(opensource_movies)", "date desc")
        ),
        MainPageData(
            "Arts & Music",
            archiveData("mediatype:(movies) AND collection:(artsandmusicvideos)")
        ),
        MainPageData(
            "TV News Archive",
            archiveData("mediatype:(movies) AND collection:(tvnews)", "date desc")
        )
    )

    private val mapper by lazy {
        jacksonObjectMapper().apply {
            configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
            configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true)
            configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, true)
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        return try {
            val (query, sort) = parseArchiveData(request.data)
            val result = advancedSearch(
                query = query,
                page = page.coerceAtLeast(1),
                sort = sort
            )

            val items = result.response?.docs
                .orEmpty()
                .mapNotNull { it.toSearchResponse(this) }
                .distinctBy { it.url }

            newHomePageResponse(
                request.name,
                items,
                hasNext = hasNext(result, page)
            )
        } catch (e: Exception) {
            logError(e)
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val keyword = query.trim()

        if (keyword.isBlank()) {
            return newSearchResponseList(emptyList(), hasNext = false)
        }

        return try {
            val archiveQuery = "mediatype:(movies) AND ($keyword)"
            val result = advancedSearch(
                query = archiveQuery,
                page = page.coerceAtLeast(1),
                sort = "downloads desc"
            )

            val items = result.response?.docs
                .orEmpty()
                .mapNotNull { it.toSearchResponse(this) }
                .distinctBy { it.url }

            newSearchResponseList(
                items,
                hasNext = hasNext(result, page)
            )
        } catch (e: Exception) {
            logError(e)
            newSearchResponseList(emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        return try {
            val identifier = extractIdentifier(url)
            val metadata = getMetadata(identifier)
            metadata.toLoadResponse(this)
        } catch (e: Exception) {
            logError(e)
            throw ErrorLoadingException("Failed to load Internet Archive item")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val loadData = tryParseJson<LoadData>(data)
            ?: runCatching {
                val identifier = data.trim()
                val metadata = getMetadata(identifier)
                metadata.buildSingleLoadData()
            }.getOrNull()
            ?: return false

        loadData.subtitleData.forEach { subtitle ->
            subtitleCallback(
                newSubtitleFile(
                    subtitle.label,
                    subtitle.url
                )
            )
        }

        val streams = loadData.urlData
            .filterNot { it.url.isBlank() }
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<URLData> { it.quality }
                    .thenByDescending { it.size }
            )

        streams.forEach { item ->
            callback(
                newExtractorLink(
                    source = name,
                    name = if (streams.size > 1) "$name (${item.displayName})" else name,
                    url = item.url
                ) {
                    quality = item.quality
                    referer = "$mainUrl/"
                }
            )
        }

        return streams.isNotEmpty()
    }

    private suspend fun advancedSearch(
        query: String,
        page: Int,
        sort: String
    ): SearchResult {
        val url = buildString {
            append("$mainUrl/advancedsearch.php")
            append("?q=${query.encodeParam()}")
            append("&fl[]=identifier")
            append("&fl[]=title")
            append("&fl[]=mediatype")
            append("&fl[]=date")
            append("&fl[]=creator")
            append("&rows=$PAGE_SIZE")
            append("&page=$page")
            append("&output=json")
            append("&sort[]=${sort.encodeParam()}")
        }

        val text = app.get(url, timeout = 30L).text
        return tryParseJson<SearchResult>(text) ?: SearchResult()
    }

    private suspend fun getMetadata(identifier: String): MetadataResult {
        val text = app.get(
            "$mainUrl/metadata/${identifier.encodePath()}",
            timeout = 30L
        ).text

        return mapper.readValue(text)
    }

    private fun parseArchiveData(data: String): Pair<String, String> {
        val parts = data.split(DATA_SEPARATOR, limit = 2)
        return parts.first().trim() to parts.getOrNull(1).orEmpty().ifBlank { "downloads desc" }
    }

    private fun hasNext(result: SearchResult, page: Int): Boolean {
        val total = result.response?.numFound ?: return false
        return total > page.coerceAtLeast(1) * PAGE_SIZE
    }

    private fun extractIdentifier(url: String): String {
        return url
            .substringAfter("/details/", url)
            .substringBefore("?")
            .substringBefore("#")
            .trim('/')
            .substringBefore("/")
            .ifBlank { url.substringAfterLast("/") }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SearchResult(
        val response: DocsResponse? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DocsResponse(
        val numFound: Int? = null,
        val docs: List<SearchEntry> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SearchEntry(
        val identifier: String? = null,
        val mediatype: String? = null,
        val title: String? = null
    ) {
        fun toSearchResponse(provider: InternetArchiveProvider): SearchResponse? {
            val id = identifier?.trim().orEmpty()
            if (id.isBlank()) return null

            val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: id
            val type = when {
                cleanTitle.contains("anime", true) -> TvType.Anime
                mediatype.equals("movies", true) -> TvType.Movie
                else -> TvType.Others
            }

            return provider.newMovieSearchResponse(
                cleanTitle,
                "${provider.mainUrl}/details/$id",
                type
            ) {
                posterUrl = "${provider.mainUrl}/services/img/$id"
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MetadataResult(
        val metadata: MediaEntry = MediaEntry(),
        val files: List<MediaFile> = emptyList()
    ) {
        private fun videoFiles(): List<MediaFile> {
            return files
                .filter { it.isVideoFile() }
                .sortedWith(
                    compareByDescending<MediaFile> { it.lengthInSeconds }
                        .thenByDescending { it.sizeBytes }
                )
        }

        private fun subtitleFiles(): List<MediaFile> {
            return files.filter { it.isSubtitleFile() }
        }

        private fun cleanDescription(): String? {
            return metadata.description
                ?.let { Jsoup.parse(it).text().trim() }
                ?.takeIf { it.isNotBlank() }
        }

        private fun tags(): List<String> {
            return metadata.subject
                .orEmpty()
                .flatMap { it.split(";") }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }

        private fun actors(): List<ActorData> {
            return metadata.creator
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .map { ActorData(Actor(it, ""), roleString = "Creator") }
        }

        private fun title(): String {
            return metadata.title?.trim()?.takeIf { it.isNotBlank() }
                ?: metadata.identifier
                ?: "Internet Archive Item"
        }

        private fun poster(provider: InternetArchiveProvider): String {
            return "${provider.mainUrl}/services/img/${metadata.identifier.orEmpty()}"
        }

        private fun getCleanedName(fileName: String): String {
            return fileName
                .substringAfterLast('/')
                .substringBeforeLast('.')
                .replace('_', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        private fun getUniqueName(file: MediaFile): String {
            return getCleanedName(file.original ?: file.name.orEmpty())
                .replace("512kb", "", ignoreCase = true)
                .replace("h 264", "", ignoreCase = true)
                .replace("ia", "", ignoreCase = true)
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { getCleanedName(file.name.orEmpty()) }
        }

        private fun getThumbnailUrl(provider: InternetArchiveProvider, fileName: String): String? {
            val thumbnail = files.firstOrNull {
                it.format.equals("Thumbnail", true) && it.original == fileName
            }

            return thumbnail?.name?.let {
                "${provider.mainUrl}/download/${metadata.identifier.orEmpty().encodePath()}/${it.encodePath()}"
            }
        }

        private fun getSubtitlesFor(videoFile: MediaFile? = null): Set<SubtitleData> {
            val videoBase = videoFile?.let { getUniqueName(it).lowercase() }

            return subtitleFiles()
                .filter { subtitle ->
                    videoBase == null ||
                        subtitle.original == videoFile?.name ||
                        getCleanedName(subtitle.name.orEmpty()).lowercase().contains(videoBase)
                }
                .mapNotNull { subtitle ->
                    val name = subtitle.name ?: return@mapNotNull null
                    SubtitleData(
                        url = "https://archive.org/download/${metadata.identifier.orEmpty().encodePath()}/${name.encodePath()}",
                        label = subtitle.subtitleLabel()
                    )
                }
                .toSet()
        }

        private fun toUrlData(file: MediaFile, provider: InternetArchiveProvider): URLData? {
            val id = metadata.identifier?.trim().orEmpty()
            val name = file.name?.trim().orEmpty()
            if (id.isBlank() || name.isBlank()) return null

            return URLData(
                url = "${provider.mainUrl}/download/${id.encodePath()}/${name.encodePath()}",
                format = file.format.orEmpty().ifBlank { name.substringAfterLast('.', "Video") },
                size = file.sizeBytes,
                quality = file.quality(),
                displayName = file.displayName()
            )
        }

        fun buildSingleLoadData(provider: InternetArchiveProvider = InternetArchiveProvider()): LoadData {
            val urls = videoFiles().mapNotNull { toUrlData(it, provider) }.toSet()

            return LoadData(
                identifier = metadata.identifier.orEmpty(),
                urlData = urls,
                subtitleData = getSubtitlesFor(),
                type = "video-playlist"
            )
        }

        suspend fun toLoadResponse(provider: InternetArchiveProvider): LoadResponse {
            val videoFiles = videoFiles()
            val groupedVideos = videoFiles.groupBy { getUniqueName(it) }
            val tags = tags()
            val baseType = when {
                tags.any { it.contains("anime", true) } -> TvType.Anime
                else -> TvType.Movie
            }

            if (groupedVideos.size <= 1) {
                val loadData = LoadData(
                    identifier = metadata.identifier.orEmpty(),
                    urlData = videoFiles.mapNotNull { toUrlData(it, provider) }.toSet(),
                    subtitleData = getSubtitlesFor(),
                    type = "video-playlist"
                )

                return provider.newMovieLoadResponse(
                    title(),
                    "${provider.mainUrl}/details/${metadata.identifier.orEmpty()}",
                    baseType,
                    loadData.toJson()
                ) {
                    plot = cleanDescription()
                    year = extractYear(metadata.date)
                    this.tags = tags
                    posterUrl = poster(provider)
                    duration = ((videoFiles.firstOrNull()?.lengthInSeconds ?: 0f) / 60).roundToInt()
                    actors = actors()
                }
            }

            val mostFrequentLength = videoFiles
                .map { (it.lengthInSeconds / 60).roundToInt() }
                .filter { it > 0 }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key

            val episodes = groupedVideos.map { (episodeName, variants) ->
                val representative = variants.maxByOrNull { it.sizeBytes } ?: variants.first()
                val episodeInfo = extractEpisodeInfo(representative.original ?: representative.name.orEmpty())
                val episodeLoadData = LoadData(
                    identifier = metadata.identifier.orEmpty(),
                    urlData = variants.mapNotNull { toUrlData(it, provider) }.toSet(),
                    subtitleData = getSubtitlesFor(representative),
                    type = "video-playlist"
                )

                provider.newEpisode(episodeLoadData.toJson()) {
                    name = representative.title?.ifBlank { null } ?: episodeName
                    season = episodeInfo.first
                    episode = episodeInfo.second
                    runTime = (representative.lengthInSeconds / 60).roundToInt().takeIf { it > 0 }
                    posterUrl = getThumbnailUrl(provider, representative.original ?: representative.name.orEmpty())
                }
            }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 9999 }, { it.name }))

            return provider.newTvSeriesLoadResponse(
                title(),
                "${provider.mainUrl}/details/${metadata.identifier.orEmpty()}",
                TvType.TvSeries,
                episodes
            ) {
                plot = cleanDescription()
                year = extractYear(metadata.date)
                this.tags = tags
                posterUrl = poster(provider)
                duration = mostFrequentLength
                actors = actors()
            }
        }

        companion object {
            private val episodePatterns by lazy {
                listOf(
                    Regex("S(\\d+)\\s*E(\\d+)", RegexOption.IGNORE_CASE),
                    Regex("Season\\s*(\\d+)\\D+Episode\\s*(\\d+)", RegexOption.IGNORE_CASE),
                    Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE),
                    Regex("Ep\\.?\\s*(\\d+)", RegexOption.IGNORE_CASE)
                )
            }

            private fun extractEpisodeInfo(fileName: String): Pair<Int?, Int?> {
                for (pattern in episodePatterns) {
                    val match = pattern.find(fileName) ?: continue
                    val values = match.groupValues

                    return when (values.size) {
                        3 -> values[1].toIntOrNull() to values[2].toIntOrNull()
                        2 -> null to values[1].toIntOrNull()
                        else -> null to null
                    }
                }

                return null to null
            }

            private fun extractYear(date: String?): Int? {
                return Regex("\\b(18|19|20)\\d{2}\\b")
                    .find(date.orEmpty())
                    ?.value
                    ?.toIntOrNull()
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MediaEntry(
        val identifier: String? = null,
        val mediatype: String? = null,
        val title: String? = null,
        val description: String? = null,
        val subject: List<String>? = null,
        val creator: List<String>? = null,
        val date: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MediaFile(
        val name: String? = null,
        val format: String? = null,
        val title: String? = null,
        val original: String? = null,
        val length: String? = null,
        val size: String? = null,
        val height: String? = null
    ) {
        val sizeBytes: Long by lazy { size?.toLongOrNull() ?: 0L }
        val lengthInSeconds: Float by lazy { calculateLengthInSeconds() }

        fun isVideoFile(): Boolean {
            val fileName = name.orEmpty().lowercase()
            val fmt = format.orEmpty().lowercase()

            if (
                fileName.endsWith("_meta.xml") ||
                fileName.endsWith("_files.xml") ||
                fileName.endsWith("_archive.torrent") ||
                fileName.endsWith("_thumbs.zip")
            ) {
                return false
            }

            val byFormat = listOf(
                "mpeg4",
                "mpeg",
                "h.264",
                "matroska",
                "webm",
                "ogg video",
                "quicktime",
                "divx",
                "cinepack",
                "512kb"
            ).any { fmt.contains(it) }

            val byExtension = listOf(
                ".mp4",
                ".m4v",
                ".mkv",
                ".webm",
                ".ogv",
                ".mov",
                ".avi",
                ".mpg",
                ".mpeg"
            ).any { fileName.endsWith(it) }

            return byFormat || byExtension
        }

        fun isSubtitleFile(): Boolean {
            val fileName = name.orEmpty().lowercase()
            val fmt = format.orEmpty().lowercase()

            return fileName.endsWith(".srt") ||
                fileName.endsWith(".vtt") ||
                fileName.endsWith(".ass") ||
                fmt.contains("subrip") ||
                fmt.contains("web video text tracks") ||
                fmt.contains("subtitle")
        }

        fun quality(): Int {
            val value = height?.toIntOrNull()
            return when {
                value == null -> qualityFromName()
                value >= 2160 -> Qualities.P2160.value
                value >= 1440 -> Qualities.P1440.value
                value >= 1080 -> Qualities.P1080.value
                value >= 720 -> Qualities.P720.value
                value >= 480 -> Qualities.P480.value
                value >= 360 -> Qualities.P360.value
                value >= 240 -> Qualities.P240.value
                value >= 144 -> Qualities.P144.value
                else -> qualityFromName()
            }
        }

        private fun qualityFromName(): Int {
            val text = "${name.orEmpty()} ${format.orEmpty()}".lowercase()
            return when {
                text.contains("2160") || text.contains("4k") -> Qualities.P2160.value
                text.contains("1440") -> Qualities.P1440.value
                text.contains("1080") -> Qualities.P1080.value
                text.contains("720") -> Qualities.P720.value
                text.contains("480") -> Qualities.P480.value
                text.contains("360") -> Qualities.P360.value
                text.contains("240") -> Qualities.P240.value
                text.contains("144") -> Qualities.P144.value
                else -> Qualities.Unknown.value
            }
        }

        fun displayName(): String {
            val qualityText = when (quality()) {
                Qualities.P2160.value -> "2160p"
                Qualities.P1440.value -> "1440p"
                Qualities.P1080.value -> "1080p"
                Qualities.P720.value -> "720p"
                Qualities.P480.value -> "480p"
                Qualities.P360.value -> "360p"
                Qualities.P240.value -> "240p"
                Qualities.P144.value -> "144p"
                else -> null
            }

            return listOfNotNull(
                qualityText,
                format?.trim()?.takeIf { it.isNotBlank() }
            ).joinToString(" - ").ifBlank { "Video" }
        }

        fun subtitleLabel(): String {
            val fileName = name.orEmpty()
            return when {
                fileName.contains("ind", true) || fileName.contains("id", true) -> "Indonesian"
                fileName.contains("eng", true) || fileName.contains("en", true) -> "English"
                else -> title?.takeIf { it.isNotBlank() } ?: "Subtitle"
            }
        }

        private fun calculateLengthInSeconds(): Float {
            val raw = length ?: return 0f
            raw.toFloatOrNull()?.let { return it }

            if (!raw.contains(":")) return 0f

            val parts = raw.split(":").map { it.toFloatOrNull() ?: 0f }
            return when (parts.size) {
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> 0f
            }
        }
    }

    data class LoadData(
        val identifier: String = "",
        val urlData: Set<URLData> = emptySet(),
        val subtitleData: Set<SubtitleData> = emptySet(),
        val type: String = "video-playlist"
    )

    data class URLData(
        val url: String = "",
        val format: String = "",
        val size: Long = 0L,
        val quality: Int = Qualities.Unknown.value,
        val displayName: String = "Video"
    )

    data class SubtitleData(
        val url: String = "",
        val label: String = "Subtitle"
    )
}

private fun String.encodeParam(): String {
    return URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}

private fun String.encodePath(): String {
    return split("/").joinToString("/") {
        URLEncoder.encode(it, "UTF-8").replace("+", "%20")
    }
}
