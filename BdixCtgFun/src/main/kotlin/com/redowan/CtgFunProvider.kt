package com.redowan

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.*

// movie.ctgfun.com is an Apache autoindex file server (no h5ai API), so
// directories are parsed from the table rows and search crawls the folders.
open class CtgFunProvider : MainAPI() {
    override var mainUrl = "https://movie.ctgfun.com"
    override var name = "(BDIX) CTG Fun FTP"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val instantLinkLoading = true
    override var lang = "bn"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    open val tvSeriesKeyword: List<String> = listOf("TV SERIES", "TV%20SERIES", "TV Series", "TV%20Series", "ANIME")

    override val mainPage = mainPageOf(
        "disk9/TV%20SERIES/" to "TV Series",
        "disk6/TV%20Series%20Part%20Three/" to "TV Series Part 3",
        "disk7/ANIME%20-%20PART%20TWO/" to "Anime",
        "disk1/English%20Movies/" to "English Movies",
        "disk3/Hindi%20Movies/" to "Hindi Movies",
        "disk4/South%20Indian%20Movies/" to "South Indian Movies",
        "disk4/Pakistani%20Movies/" to "Pakistani Movies",
        "disk5/4K%20Movies%20Collection/" to "4K Movies",
        "disk7/IMDB%20TOP%20MOVIES/" to "IMDb Top Movies",
    )

    // -------- TMDB metadata (same approach as the DhakaFlix providers) --------

    data class TmdbSearchResponse(val results: List<TmdbItem>?)
    data class TmdbItem(
        val id: Int?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        val vote_average: Double? = null
    )

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""^\d{1,3}\.\s*"""), "")
            .replace(Regex("""\s*\([^)]*?\d{4}.*?\).*$"""), "")
            .replace("  ", " ")
            .trim()
    }

    // Folder names carry the release year (e.g. "Avatar (2009) Extended Hindi-English 2160p");
    // use it to pick the right TMDB entry when the same title has multiple eras.
    private fun yearFromName(title: String): Int? {
        return Regex("""\b(?:19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
    }

    private fun TmdbItem.yearInt(): Int? =
        (firstAirDate ?: releaseDate)?.take(4)?.toIntOrNull()

    private suspend fun getExternalMetadata(rawName: String, isTv: Boolean): TmdbItem? {
        val clean = cleanTitle(rawName)
        val folderYear = yearFromName(rawName)
        val type = if (isTv) "tv" else "movie"
        return try {
            val params = mutableMapOf(
                "api_key" to "e6333b32409e02a4a6eba6fb7ff866bb",
                "query" to clean
            )
            if (folderYear != null) {
                params[if (isTv) "first_air_date_year" else "year"] = folderYear.toString()
            }
            val results = app.get(
                "https://api.themoviedb.org/3/search/$type",
                params = params,
                timeout = 10,
                cacheTime = 60
            ).parsed<TmdbSearchResponse>().results ?: emptyList()

            val search = if (folderYear != null) {
                results.firstOrNull { (it.yearInt() ?: Int.MIN_VALUE) in (folderYear - 1)..(folderYear + 1) }
                    ?: results.firstOrNull()
            } else results.firstOrNull()
            search
        } catch (_: Exception) {
            null
        }
    }

    // -------- autoindex listing parsing --------

    data class Entry(val name: String, val url: String)

    private val videoRegex = Regex("""\.(mkv|mp4|avi|m4v|ts)(\?.*)?$""", RegexOption.IGNORE_CASE)

    private fun resolveLink(base: String, href: String): String {
        return if (href.startsWith("http")) href
        else if (href.startsWith("/")) mainUrl + href
        else base.trimEnd('/') + "/" + href
    }

    /** Splits one autoindex page into its subfolders and direct video files. */
    private suspend fun listDir(url: String): Pair<List<Entry>, List<Entry>> {
        return try {
            val doc = app.get(url, cacheTime = 10).document
            val dirs = mutableListOf<Entry>()
            val files = mutableListOf<Entry>()
            doc.select("tr").forEach { row: Element ->
                val isDir = row.selectFirst("img[alt=\"[DIR]\"]") != null
                val isParent = row.selectFirst("img[alt=\"[PARENTDIR]\"]") != null
                val a = row.selectFirst("td a") ?: return@forEach
                val href = a.attr("href")
                if (href.isEmpty() || href.startsWith("?") || href.startsWith("/") || isParent) return@forEach
                val full = resolveLink(url, href)
                val name = URLDecoder.decode(href.trimEnd('/'), StandardCharsets.UTF_8.toString())
                if (isDir) {
                    dirs.add(Entry(name, full))
                } else if (videoRegex.containsMatchIn(href)) {
                    files.add(Entry(name, full))
                }
            }
            Pair(dirs, files)
        } catch (_: Exception) {
            Pair(emptyList(), emptyList())
        }
    }

    private fun containsAnyLoop(text: String, keywords: List<String>): Boolean {
        for (keyword in keywords) {
            if (text.contains(keyword, ignoreCase = true)) return true
        }
        return false
    }

    private val nameRegex = Regex(""".*/([^/]+)(?:/[^/]*)*$""")
    private fun nameFromUrl(href: String): String {
        val hrefDecoded = URLDecoder.decode(href, StandardCharsets.UTF_8.toString())
        val name = nameRegex.find(hrefDecoded)?.groups?.get(1)?.value
        return name.toString()
    }

    private suspend fun toSearchResult(entry: Entry, isTv: Boolean): SearchResponse? {
        val meta = getExternalMetadata(entry.name, isTv)
        val tmdbPoster = meta?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        return newAnimeSearchResponse(entry.name, entry.url, if (isTv) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = tmdbPoster
            addDubStatus("dual" in entry.name.lowercase() || "dubbed" in entry.name.lowercase())
        }
    }

    // -------- home page --------

    private val yearFolderRegex = Regex("""^\d{4}$""")
    private val letterFolderRegex = Regex("""^[A-Z]$""")

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse = coroutineScope {
        val (dirs, _) = listDir("$mainUrl/${request.data}")

        // English movies live in year folders and Hindi movies mix year folders with
        // direct entries, so collapse year folders into their contents. TV part 2 is
        // organized in single-letter folders, so collapse those as well.
        val yearFolders = dirs.filter { yearFolderRegex.matches(it.name) }
        val letterFolders = dirs.filter { letterFolderRegex.matches(it.name) }
        val entries: List<Entry> = when {
            yearFolders.isNotEmpty() -> {
                dirs.filterNot { yearFolderRegex.matches(it.name) } +
                    yearFolders.take(20).flatMap { (dir, _) -> listDir(dir).first }
            }
            letterFolders.size >= 10 -> {
                dirs.filterNot { letterFolderRegex.matches(it.name) } +
                    letterFolders.flatMap { (dir, _) -> listDir(dir).first }
            }
            else -> dirs
        }

        val home = entries.take(15).map { entry ->
            async {
                val isTv = containsAnyLoop(entry.url, tvSeriesKeyword)
                toSearchResult(entry, isTv)
            }
        }.awaitAll().filterNotNull()
        newHomePageResponse(request.name, home, false)
    }

    // -------- detail page --------

    private suspend fun collectEpisodes(url: String, seasonNum: Int, episodes: MutableList<Episode>, depth: Int) {
        val (dirs, files) = listDir(url)
        var episodeNum = episodes.count { it.season == seasonNum }
        files.forEach { file ->
            episodeNum++
            episodes.add(
                newEpisode(file.url) {
                    this.name = file.name
                    this.season = seasonNum
                    this.episode = episodeNum
                }
            )
        }
        // quality subfolders etc. keep belonging to the same season
        if (depth < 2) {
            dirs.forEach { dir ->
                collectEpisodes(dir.url, seasonNum, episodes, depth + 1)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val rawName = nameFromUrl(url)
        val isTv = containsAnyLoop(url, tvSeriesKeyword)

        val (dirs, files) = listDir(url)

        if (isTv) {
            val episodesData = mutableListOf<Episode>()
            var seasonNum = 0
            // season-pack folders; each one becomes a season
            dirs.forEach { dir ->
                seasonNum++
                collectEpisodes(dir.url, seasonNum, episodesData, 0)
            }
            // loose episode files directly in the show folder
            if (files.isNotEmpty()) {
                seasonNum++
                var episodeNum = 0
                files.forEach { file ->
                    episodeNum++
                    episodesData.add(
                        newEpisode(file.url) {
                            this.name = file.name
                            this.season = seasonNum
                            this.episode = episodeNum
                        }
                    )
                }
            }
            val meta = getExternalMetadata(rawName, isTv)
            newTvSeriesLoadResponse(rawName, url, TvType.TvSeries, episodesData) {
                this.posterUrl = meta?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                this.plot = meta?.overview
                this.year = (meta?.firstAirDate ?: meta?.releaseDate)?.split("-")?.firstOrNull()?.toIntOrNull()
            }
        } else {
            // movie folder: video directly inside, otherwise one level of subfolders
            // (quality splits like 1080p/720p)
            var videoUrl = files.firstOrNull()?.url
            if (videoUrl == null) {
                for (dir in dirs.take(10)) {
                    val (subDirs, subFiles) = listDir(dir.url)
                    videoUrl = subFiles.firstOrNull()?.url
                    if (videoUrl != null) break
                }
            }
            val meta = getExternalMetadata(rawName, false)
            newMovieLoadResponse(rawName, url, TvType.Movie, videoUrl ?: "") {
                this.posterUrl = meta?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                this.plot = meta?.overview
                this.year = meta?.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
            }
        }
    }

    // -------- search: crawl the known folders, no search API on autoindex --------

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val tvRoots = listOf(
            "disk9/TV%20SERIES/",
            "disk6/TV%20Series%20Part%20Three/",
            "disk7/ANIME%20-%20PART%20TWO/"
        )
        val movieRoots = listOf(
            "disk3/Hindi%20Movies/",
            "disk4/South%20Indian%20Movies/",
            "disk4/Pakistani%20Movies/",
            "disk5/4K%20Movies%20Collection/",
            "disk7/IMDB%20TOP%20MOVIES/"
        )

        val tvEntries = async {
            tvRoots.flatMap { root ->
                val (dirs, _) = listDir("$mainUrl/$root")
                // part 2 is letter-organized; crawl its letters too
                val letters = dirs.filter { letterFolderRegex.matches(it.name) }
                if (letters.size >= 10) {
                    dirs.filterNot { letterFolderRegex.matches(it.name) } +
                        letters.flatMap { (dir, _) -> listDir(dir).first }
                } else dirs
            }
        }

        val movieEntries = async {
            val results = mutableListOf<Entry>()
            movieRoots.forEach { root ->
                val (dirs, _) = listDir("$mainUrl/$root")
                val yearFolders = dirs.filter { yearFolderRegex.matches(it.name) }
                results += dirs.filterNot { yearFolderRegex.matches(it.name) }
                // year folders hide the actual movies one level down
                yearFolders.take(15).forEach { dir ->
                    results += listDir(dir.url).first
                }
            }
            results
        }

        val lowerQuery = query.trim().lowercase()
        if (lowerQuery.isEmpty()) return@coroutineScope emptyList()

        val matched = (tvEntries.await().map { it to true } + movieEntries.await().map { it to false })
            .filter { it.first.name.lowercase().contains(lowerQuery) }
            .take(40)

        matched.map { (entry, isTv) ->
            async { toSearchResult(entry, isTv) }
        }.awaitAll().filterNotNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            newExtractorLink(
                data, this.name, url = data, type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }
}
