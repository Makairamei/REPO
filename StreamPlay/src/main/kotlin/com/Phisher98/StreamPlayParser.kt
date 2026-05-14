package com.phisher98

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.annotations.SerializedName
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

//Anichi

data class AnichiRoot(
    val data: AnichiData,
)

data class AnichiData(
    val shows: AnichiShows,
)

data class AnichiShows(
    val pageInfo: PageInfo,
    val edges: List<Edge>,
)

data class PageInfo(
    val total: Long,
)

data class Edge(
    @param:JsonProperty("_id")
    val id: String,
    val name: String,
    val englishName: String,
    val nativeName: String,
)

//Anichi Ep Parser

data class AnichiEP(
    val data: AnichiEPData,
)

data class AnichiEPData(
    val episode: AnichiEpisode,
)

data class AnichiEpisode(
    val sourceUrls: List<SourceUrl>,
)

data class SourceUrl(
    val sourceUrl: String,
    val sourceName: String,
    val downloads: AnichiDownloads?,
)

data class AnichiDownloads(
    val sourceName: String,
    val downloadUrl: String,
)

data class AniIds(var id: Int? = null, var idMal: Int? = null)

data class TmdbDate(
    val today: String,
    val nextWeek: String,
    val lastWeekStart: String,
    val monthStart: String
)

data class MoflixResponse(
    @param:JsonProperty("title") val title: Episode? = null,
    @param:JsonProperty("episode") val episode: Episode? = null,
) {
    data class Episode(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("videos") val videos: ArrayList<Videos>? = arrayListOf(),
    ) {
        data class Videos(
            @param:JsonProperty("name") val name: String? = null,
            @param:JsonProperty("category") val category: String? = null,
            @param:JsonProperty("src") val src: String? = null,
            @param:JsonProperty("quality") val quality: String? = null,
        )
    }
}

data class AniMedia(
    @param:JsonProperty("id") var id: Int? = null,
    @param:JsonProperty("idMal") var idMal: Int? = null
)

data class AniPage(
    @param:JsonProperty("media") var media: ArrayList<AniMedia> = arrayListOf()
)

data class AniData(
    @param:JsonProperty("Page") var Page: AniPage? = null,
    @param:JsonProperty("media") var media: ArrayList<AniMedia>? = null
)

data class AniSearch(
    @param:JsonProperty("data") var data: AniData? = null
)


//WyZIESUBAPI

data class WyZIESUB(
    val id: String,
    val url: String,
    val flagUrl: String,
    val format: String,
    val display: String,
    val language: String,
    val media: String,
    val isHearingImpaired: Boolean,
)

data class ResponseHash(
    @param:JsonProperty("embed_url") val embed_url: String,
    @param:JsonProperty("key") val key: String? = null,
    @param:JsonProperty("type") val type: String? = null,
)

data class KisskhSources(
    @param:JsonProperty("Video") val video: String?,
    @param:JsonProperty("ThirdParty") val thirdParty: String?,
)

data class KisskhSubtitle(
    @param:JsonProperty("src") val src: String?,
    @param:JsonProperty("label") val label: String?,
)

data class KisskhEpisodes(
    @param:JsonProperty("id") val id: Int?,
    @param:JsonProperty("number") val number: Int?,
)

data class KisskhDetail(
    @param:JsonProperty("episodes") val episodes: ArrayList<KisskhEpisodes>? = arrayListOf(),
)

data class KisskhResults(
    @param:JsonProperty("id") val id: Int?,
    @param:JsonProperty("title") val title: String?,
)

data class ZShowEmbed(
    @param:JsonProperty("m") val meta: String? = null,
)

data class WatchsomuchTorrents(
    @param:JsonProperty("id") val id: Int? = null,
    @param:JsonProperty("movieId") val movieId: Int? = null,
    @param:JsonProperty("season") val season: Int? = null,
    @param:JsonProperty("episode") val episode: Int? = null,
)

data class WatchsomuchMovies(
    @param:JsonProperty("torrents") val torrents: ArrayList<WatchsomuchTorrents>? = arrayListOf(),
)

data class WatchsomuchResponses(
    @param:JsonProperty("movie") val movie: WatchsomuchMovies? = null,
)

data class WatchsomuchSubtitles(
    @param:JsonProperty("url") val url: String? = null,
    @param:JsonProperty("label") val label: String? = null,
)

data class WatchsomuchSubResponses(
    @param:JsonProperty("subtitles") val subtitles: ArrayList<WatchsomuchSubtitles>? = arrayListOf(),
)

data class IndexMedia(
    @param:JsonProperty("id") val id: String? = null,
    @param:JsonProperty("driveId") val driveId: String? = null,
    @param:JsonProperty("mimeType") val mimeType: String? = null,
    @param:JsonProperty("size") val size: String? = null,
    @param:JsonProperty("name") val name: String? = null,
    @param:JsonProperty("modifiedTime") val modifiedTime: String? = null,
)

data class IndexData(
    @param:JsonProperty("files") val files: ArrayList<IndexMedia>? = arrayListOf(),
)

data class JikanExternal(
    @param:JsonProperty("name") val name: String? = null,
    @param:JsonProperty("url") val url: String? = null,
)

data class JikanData(
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("external") val external: List<JikanExternal>? = null,
    @param:JsonProperty("season") val season: String? = null,
)

data class JikanResponse(
    @param:JsonProperty("data") val data: JikanData? = null,
)

//Hianime

data class EpisodeServers(
    val type: String? = null,
    val link: String? = null,
    val server: Long? = null,
    val sources: List<Any?>? = null,
    val tracks: List<Any?>? = null
)


//anime animepahe parser

data class animepahe(
    val total: Long,
    @param:JsonProperty("per_page")
    val perPage: Long,
    @param:JsonProperty("current_page")
    val currentPage: Long,
    @param:JsonProperty("last_page")
    val lastPage: Long,
    @param:JsonProperty("next_page_url")
    val nextPageUrl: Any?,
    @param:JsonProperty("prev_page_url")
    val prevPageUrl: Any?,
    val from: Long,
    val to: Long,
    val data: List<Daum>,
)

data class Daum(
    val id: Long,
    @param:JsonProperty("anime_id")
    val animeId: Long,
    val episode: Int,
    val episode2: Long,
    val edition: String,
    val title: String,
    val snapshot: String,
    val disc: String,
    val audio: String,
    val duration: String,
    val session: String,
    val filler: Long,
    @param:JsonProperty("created_at")
    val createdAt: String,
)


data class MALSyncSites(
    @param:JsonProperty("AniXL") val AniXL: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
    @param:JsonProperty("Zoro") val zoro: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
    @param:JsonProperty("9anime") val nineAnime: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
    @param:JsonProperty("animepahe") val animepahe: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
    @param:JsonProperty("KickAssAnime") val KickAssAnime: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
    @param:JsonProperty("AnimeKAI") val AnimeKAI: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
)
data class MALSyncResponses(
    @param:JsonProperty("Sites") val sites: MALSyncSites? = null,
)

data class HianimeResponses(
    @param:JsonProperty("html") val html: String? = null,
    @param:JsonProperty("link") val link: String? = null,
)

data class AllMovielandEpisodeFolder(
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("id") val id: String? = null,
    @param:JsonProperty("file") val file: String? = null,
)

data class AllMovielandSeasonFolder(
    @param:JsonProperty("episode") val episode: String? = null,
    @param:JsonProperty("id") val id: String? = null,
    @param:JsonProperty("folder") val folder: ArrayList<AllMovielandEpisodeFolder>? = arrayListOf(),
)

data class AllMovielandServer(
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("id") val id: String? = null,
    @param:JsonProperty("file") val file: String? = null,
    @param:JsonProperty("folder") val folder: ArrayList<AllMovielandSeasonFolder>? = arrayListOf(),
)

data class AllMovielandPlaylist(
    @param:JsonProperty("file") val file: String? = null,
    @param:JsonProperty("key") val key: String? = null,
    @param:JsonProperty("href") val href: String? = null,
)

data class ShowflixResultsMovies(
    @param:JsonProperty("objectId")
    val objectId: String? = null,
    @param:JsonProperty("name")
    val name: String? = null,
    @param:JsonProperty("releaseYear")
    val releaseYear: Int? = null,
    @param:JsonProperty("tmdbId")
    val tmdbId: Int? = null,
    @param:JsonProperty("embedLinks")
    val embedLinks: Map<String, String>? = null,
    @param:JsonProperty("languages")
    val languages: List<String>? = null,
    @param:JsonProperty("genres")
    val genres: List<String>? = null,
    @param:JsonProperty("backdropURL")
    val backdropURL: String? = null,
    @param:JsonProperty("posterURL")
    val posterURL: String? = null,
    @param:JsonProperty("hdLink")
    val hdLink: String? = null,
    @param:JsonProperty("hubCloudLink")
    val hubCloudLink: String? = null,
    @param:JsonProperty("storyline")
    val storyline: String? = null,
    @param:JsonProperty("rating")
    val rating: String? = null,
    @param:JsonProperty("createdAt")
    val createdAt: String? = null,
    @param:JsonProperty("updatedAt")
    val updatedAt: String? = null
)

data class ShowflixResultsSeries(
    @param:JsonProperty("objectId")
    val objectId: String? = null,
    @param:JsonProperty("seriesName")
    val seriesName: String? = null,
    @param:JsonProperty("releaseYear")
    val releaseYear: Int? = null,
    @param:JsonProperty("tmdbId")
    val tmdbId: Int? = null,
    @param:JsonProperty("streamwish")
    val streamwish: Map<String, List<String>>? = null,
    @param:JsonProperty("filelions")
    val filelions: Map<String, List<String>>? = null,
    @param:JsonProperty("streamruby")
    val streamruby: Map<String, List<String>>? = null,
    @param:JsonProperty("languages")
    val languages: List<String>? = null,
    @param:JsonProperty("genres")
    val genres: List<String>? = null,
    @param:JsonProperty("backdropURL")
    val backdropURL: String? = null,
    @param:JsonProperty("posterURL")
    val posterURL: String? = null,
    @param:JsonProperty("hdLink")
    val hdLink: String? = null,
    @param:JsonProperty("hubCloudLink")
    val hubCloudLink: String? = null,
    @param:JsonProperty("storyline")
    val storyline: String? = null,
    @param:JsonProperty("rating")
    val rating: String? = null,
    @param:JsonProperty("createdAt")
    val createdAt: String? = null,
    @param:JsonProperty("updatedAt")
    val updatedAt: String? = null
)

data class RidoContentable(
    @param:JsonProperty("imdbId") var imdbId: String? = null,
    @param:JsonProperty("tmdbId") var tmdbId: Int? = null,
)

data class RidoItems(
    @param:JsonProperty("slug") var slug: String? = null,
    @param:JsonProperty("contentable") var contentable: RidoContentable? = null,
)

data class RidoData(
    @param:JsonProperty("url") var url: String? = null,
    @param:JsonProperty("items") var items: ArrayList<RidoItems>? = arrayListOf(),
)

data class RidoResponses(
    @param:JsonProperty("data") var data: ArrayList<RidoData>? = arrayListOf(),
)

data class RidoSearch(
    @param:JsonProperty("data") var data: RidoData? = null,
)

data class NepuSearch(
    @param:JsonProperty("data") val data: ArrayList<Data>? = arrayListOf(),
) {
    data class Data(
        @param:JsonProperty("url") val url: String? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("type") val type: String? = null,
    )
}
data class SubtitlesAPI(
    val subtitles: List<Subtitle>,
    val cacheMaxAge: Long,
)
data class Subtitle(
    val id: String,
    val url: String,
    @param:JsonProperty("SubEncoding")
    val subEncoding: String,
    val lang: String,
    val m: String,
    val g: String,
)

data class RiveStreamSource(
    val data: List<String>
)

data class KisskhKey(
    val id: String,
    val version: String,
    val key: String,
)

//SuperStream


data class FebResponse(
    val success: Boolean?,
    val versions: List<Version>?
)

data class Version(
    val name: String?,
    val links: List<Link>?
)

data class Link(
    val url: String?,
    val quality: String?,
    val name: String?,
    val size: String?
)

data class ER(
    @param:JsonProperty("code") val code: Int? = null,
    @param:JsonProperty("msg") val msg: String? = null,
    @param:JsonProperty("server_runtime") val serverRuntime: Double? = null,
    @param:JsonProperty("server_name") val serverName: String? = null,
    @param:JsonProperty("data") val data: DData? = null,
)

data class DData(
    @param:JsonProperty("link") val link: String? = null,
    @param:JsonProperty("file_list") val fileList: List<FileList>? = null,
)

data class FileList(
    @param:JsonProperty("fid") val fid: Long? = null,
    @param:JsonProperty("file_name") val fileName: String? = null,
    @param:JsonProperty("oss_fid") val ossFid: Long? = null,
)

data class ExternalResponse(
    @param:JsonProperty("code") val code: Int? = null,
    @param:JsonProperty("msg") val msg: String? = null,
    @param:JsonProperty("server_runtime") val serverRuntime: Double? = null,
    @param:JsonProperty("server_name") val serverName: String? = null,
    @param:JsonProperty("data") val data: Data? = null,
) {
    data class Data(
        @param:JsonProperty("link") val link: String? = null,
        @param:JsonProperty("file_list") val fileList: List<FileList>? = null,
    ) {
        data class FileList(
            @param:JsonProperty("fid") val fid: Long? = null,
            @param:JsonProperty("file_name") val fileName: String? = null,
            @param:JsonProperty("oss_fid") val ossFid: Long? = null,
        )
    }
}

data class ExternalSourcesWrapper(
    @param:JsonProperty("sources") val sources: List<ExternalSources>? = null
)

data class ExternalSources(
    @param:JsonProperty("source") val source: String? = null,
    @param:JsonProperty("file") val file: String? = null,
    @param:JsonProperty("label") val label: String? = null,
    @param:JsonProperty("type") val type: String? = null,
    @param:JsonProperty("size") val size: String? = null,
)

data class EpisoderesponseKAA(
    val slug: String,
    val title: String,
    val duration_ms: Long,
    val episode_number: Number,
    val episode_string: String,
    val thumbnail: ThumbnailKAA
)

data class ThumbnailKAA(
    val formats: List<String>,
    val sm: String,
    val aspectRatio: Double,
    val hq: String
)


data class ServersResKAA(
    val servers: List<ServerKAA>,

    )

data class ServerKAA(
    val name: String,
    val shortName: String,
    val src: String,
)


data class EncryptedKAA(
    val data: String,
)


data class m3u8KAA(
    val hls: String,
    val subtitles: List<SubtitleKAA>,
    val key: String,
)

data class SubtitleKAA(
    val language: String,
    val name: String,
    val src: String,
)


data class AnimeKaiResponse(
    @param:JsonProperty("result") val result: String
) {
    fun getDocument(): Document {
        return Jsoup.parse(result)
    }
}

fun extractVideoUrlFromJsonAnimekai(jsonData: String): String {
    val jsonObject = JSONObject(jsonData)
    return jsonObject.getString("url")
}
data class AnichiStream(
    @param:JsonProperty("format") val format: String? = null,
    @param:JsonProperty("audio_lang") val audio_lang: String? = null,
    @param:JsonProperty("hardsub_lang") val hardsub_lang: String? = null,
    @param:JsonProperty("url") val url: String? = null,
)

data class PortData(
    @param:JsonProperty("streams") val streams: ArrayList<AnichiStream>? = arrayListOf(),
)

data class AnichiSubtitles(
    @param:JsonProperty("lang") val lang: String?,
    @param:JsonProperty("label") val label: String?,
    @param:JsonProperty("src") val src: String?,
)

data class AnichiLinks(
    @param:JsonProperty("link") val link: String,
    @param:JsonProperty("hls") val hls: Boolean? = null,
    @param:JsonProperty("resolutionStr") val resolutionStr: String,
    @param:JsonProperty("src") val src: String? = null,
    @param:JsonProperty("headers") val headers: Headers? = null,
    @param:JsonProperty("portData") val portData: PortData? = null,
    @param:JsonProperty("subtitles") val subtitles: ArrayList<AnichiSubtitles>? = arrayListOf(),
)

data class Headers(
    @param:JsonProperty("Referer") val referer: String? = null,
    @param:JsonProperty("Origin") val origin: String? = null,
    @param:JsonProperty("user-agent") val userAgent: String? = null,
)


data class AnichiVideoApiResponse(@param:JsonProperty("links") val links: List<AnichiLinks>)

//Domains Parser

data class DomainsParser(
    val moviesdrive: String,
    @param:JsonProperty("HDHUB4u")
    val hdhub4u: String,
    @param:JsonProperty("4khdhub")
    val n4khdhub: String,
    @param:JsonProperty("MultiMovies")
    val multiMovies: String,
    val bollyflix: String,
    @param:JsonProperty("UHDMovies")
    val uhdmovies: String,
    val moviesmod: String,
    val topMovies: String,
    val hdmovie2: String,
    val vegamovies: String,
    val rogmovies: String,
    val luxmovies: String,
    val movierulzhd: String,
    val extramovies: String,
    val banglaplex: String,
    val toonstream: String,
    val telugumv: String,
    val filmycab: String,
    val tellyhd: String,
    val filmyfiy: String,
    val hindmoviez: String,
    val tamilblasters: String,
    val hubcloud: String,
    val movienestbd: String,
    val movies4u: String,
    val cinevood: String,
    val dudefilms: String,
    val fibwatch: String,
    val fibtoon: String,
    val fibdrama: String,
    val xprimehub: String,
    val m4ufree: String,
)


// CinemetaRes

data class CinemetaRes(
    val meta: Meta? = null
) {

    data class Meta(
        val id: String? = null,
        val type: String? = null,
        val name: String? = null,

        @param:JsonProperty("imdb_id")
        val imdbId: String? = null,

        val slug: String? = null,

        val director: String? = null,
        val writer: String? = null,

        val description: String? = null,
        val year: String? = null,
        val releaseInfo: String? = null,
        val released: String? = null,
        val runtime: String? = null,
        val status: String? = null,
        val country: String? = null,
        val imdbRating: String? = null,
        val genres: List<String>? = null,
        val poster: String? = null,
        @param:JsonProperty("_rawPosterUrl")
        val rawPosterUrl: String? = null,

        val background: String? = null,
        val logo: String? = null,

        val videos: List<Video>? = null,
        val trailers: List<Trailer>? = null,
        val trailerStreams: List<TrailerStream>? = null,
        val links: List<Link>? = null,

        val behaviorHints: BehaviorHints? = null,

        @param:JsonProperty("app_extras")
        val appExtras: AppExtras? = null,
    ) {

        data class BehaviorHints(
            val defaultVideoId: Any? = null,
            val hasScheduledVideos: Boolean? = null
        )

        data class Link(
            val name: String? = null,
            val category: String? = null,
            val url: String? = null
        )

        data class Trailer(
            val source: String? = null,
            val type: String? = null,
            val name: String? = null
        )

        data class TrailerStream(
            val ytId: String? = null,
            val title: String? = null
        )

        data class Video(
            val id: String? = null,
            val title: String? = null,
            val season: Int? = null,
            val episode: Int? = null,
            val thumbnail: String? = null,
            val overview: String? = null,
            val released: String? = null,
            val available: Boolean? = null,
            val runtime: String? = null
        )

        data class AppExtras(
            val cast: List<Cast>? = null,
            val directors: List<Any?>? = null,
            val writers: List<Any?>? = null,
            val seasonPosters: List<String?>? = null,
            val certification: String? = null
        )

        data class Cast(
            val name: String? = null,
            val character: String? = null,
            val photo: String? = null
        )
    }
}

data class Watch32(
    val type: String,
    val link: String,
)

data class MorphDaum(
    val title: String,
    val link: String,
)

data class CinemaOSReponse(
    val data: CinemaOSReponseData,
    val encrypted: Boolean,
)

data class CinemaOSReponseData(
    val encrypted: String,
    val cin: String,
    val mao: String,
    val salt: String,
)

//Vidlink
data class VidlinkResponse(
    @SerializedName("stream") val stream: VidlinkStream
)

data class VidlinkStream(
    @SerializedName("playlist") val playlist: String
)
data class VidlinkCaption(
    val id: String,
    val url: String,
    val language: String,
    val type: String,
    val hasCorsRestrictions: Boolean,
)

data class PrimeSrcServerList(
    val servers: List<PrimeSrcServer>,
)

data class PrimeSrcServer(
    val name: String,
    val key: String,
    @param:JsonProperty("file_size")
    val fileSize: String?,
    @param:JsonProperty("file_name")
    val fileName: String?,
)


data class VidFastRes(
    val status: Long,
    val result: VidFastResult,
    val info: String,
)

data class VidFastResult(
    val servers: String,
    val stream: String,
    val token: String,
)


data class VidFastServers(
    val result: List<VidFastServersResult>,
)

data class VidFastServersResult(
    val name: String,
    val data: String,
)

data class VidFastServersStreamRoot(
    val status: Long,
    val result: VidFastServersStreamResult,
    val info: String?
)

data class VidFastServersStreamResult(
    val url: String?,
    val tracks: List<VidFastServersTrack>?,
    val noReferrer: Boolean?
)

data class VidFastServersTrack(
    val file: String?,
    val label: String?
)

data class NuvioStreams(
    val streams: List<NuvioStreamsStream>,
)

data class NuvioStreamsStream(
    val name: String,
    val title: String,
    val url: String,
    val type: String,
    val availability: Long,
    val behaviorHints: NuvioStreamsBehaviorHints,
)

data class NuvioStreamsBehaviorHints(
    val notWebReady: Boolean,
)

data class YflixResponse(
    @get:JsonProperty("result") val result: String
) {
    fun getDocument(): Document {
        return Jsoup.parse(result)
    }
}

class SearchData : ArrayList<SearchData.SearchDataItem>() {
    data class SearchDataItem(
        val audio_languages: String,
        val exact_match: Int,
        val id: Int,
        val path: String,
        val poster: String,
        val qualities: List<String>,
        val release_year: String,
        val title: String,
        val tmdb_id: Int,
        val type: String
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmbedmasterSourceItem(
    val id: Any? = null,
    val type: String? = null,
    @param:JsonProperty("source_type")
    val sourceType: Long? = null,
    @param:JsonProperty("source_quality")
    val sourceQuality: String? = null,
    @param:JsonProperty("source_name")
    val sourceName: String? = null,
    @param:JsonProperty("source_title")
    val sourceTitle: String? = null,
    @param:JsonProperty("source_subtitle")
    val sourceSubtitle: Boolean? = null,
    @param:JsonProperty("source_icon")
    val sourceIcon: Long? = null,
    @param:JsonProperty("source_url")
    val sourceUrl: String,
)

data class Server(
    val name: String,
    val quality: String,
    val type: String,
    val url: String,
    val headers: BidSrcHeaders?,
)

data class BidSrcHeaders(
    @param:JsonProperty("Referer")
    val referer: String,
    @param:JsonProperty("Origin")
    val origin: String,
)

data class Flixindia(
    val results: List<FlixindiaResult>,
    val query: String,
    val count: Long,
)

data class FlixindiaResult(
    val id: Long,
    val title: String,
    val url: String,
    @param:JsonProperty("created_at")
    val createdAt: String,
)


//Vegamovies

data class VegamoviesResponse(
    val found: Int?,
    val hits: List<VegamoviesHit>?
)

data class VegamoviesHit(
    val document: VegamoviesDocument?
)

data class VegamoviesDocument(
    val id: String?,
    val imdb_id: String?,
    val permalink: String?,
    val post_title: String?,
    val post_thumbnail: String?
)

//Dooflix

data class Dooflix(
    val id: Long,
    val links: List<DooflixLink>,
)

data class DooflixLink(
    val id: Long,
    @param:JsonProperty("movie_id")
    val movieId: Long,
    val host: String,
    val url: String,
    val quality: String,
    val size: String,
    val order: Long,
    @param:JsonProperty("created_at")
    val createdAt: String,
    @param:JsonProperty("updated_at")
    val updatedAt: String,
)

//kuudere
data class KuudereSearch(
    val success: Boolean?,
    val results: List<KuudereResult>?
)

data class KuudereResult(
    val id: String?,
    val title: String?,
    val details: String?
)

data class KuudereWatch(
    val episode_links: List<KuudereEpisodeLink>?
)

data class KuudereEpisodeLink(
    val serverId: Int?,
    val serverName: String?,
    val episodeNumber: Int?,
    val dataType: String?,
    val dataLink: String?,
)

//Hexa

data class HexaEn(
    val status: Long,
    val result: HexResult,
)

data class HexResult(
    val token: String,
    val expires: String,
)

data class HexaResponse(
    val status: Int? = null,
    val result: HexaResult? = null
)

data class HexaResult(
    val sources: List<HexaSource>? = null,
    val skipTime: Any? = null
)

data class HexaSource(
    val server: String? = null,
    val url: String? = null
)

//MegaPlaybuzz HiAnime

data class HiAnimeSourcesResponse(
    val sources: HiAnimeSources?,
    val tracks: List<HiAnimeTrack>?,
    val t: Long?,
    val server: Long?,
)

data class HiAnimeSources(
    val file: String?,
)

data class HiAnimeTrack(
    val file: String?,
    val label: String?,
    val kind: String?,
    val default: Boolean?
)

data class Moviesdrive(
    val hits: List<Hit>,
)

data class Hit(
    val url: String,
)

//vaplayer

data class Vaplayer(
    val data: VaplayerData,
    @param:JsonProperty("default_subs")
    val defaultSubs: List<Any?>,
)

data class VaplayerData(
    @param:JsonProperty("stream_urls")
    val streamUrls: List<String>,
)

//ReAnime

data class ReAnime(
    val success: Boolean,
    val servers: List<ReAnimeServer>,
)

data class ReAnimeServer(
    @param:JsonProperty($$"$id")
    val id: String,
    val serverName: String,
    val dataLink: String,
    val dataType: String,
    @param:JsonProperty("continue")
    val continue_field: Boolean,
    val softsub: Boolean,
)

data class ResolvedReAnime(
    val result: ResolvedReAnimeResult,
)

data class ResolvedReAnimeResult(
    val token: String,
    val state: ResolvedReAnimeState,
)

data class ResolvedReAnimeState(
    val token: String,
)

data class ReAnimeStream(
    val result: ReAnimeStreamResult,
)

data class ReAnimeStreamResult(
    val stream: String,
)

