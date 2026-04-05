package com.mlsbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MlsbdProvider : MainAPI() {

    override var mainUrl = "https://www.jalshamoviez.lifestyle"
    override var name = "JalshaMoviez"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Others,
    )

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/69/bengali-webseries-hd/default/1.html"     to "Bengali Web Series",
        "$mainUrl/category/1/bollywood-movies/default/1.html"           to "Bollywood Movies",
        "$mainUrl/category/5/hollywood-hindi-dubbed-movies/default/1.html" to "Hollywood Hindi Dubbed",
        "$mainUrl/category/3/south-indian-movies-dubbed-in-hindi/default/1.html" to "South Indian Hindi Dubbed",
        "$mainUrl/category/68/web-series-free-download/default/1.html"  to "Web Series",
        "$mainUrl/category/70/hindi-webseries-hd/default/1.html"        to "Hindi Web Series",
        "$mainUrl/category/2/hollywood-movies/default/1.html"           to "Hollywood Movies",
        "$mainUrl/category/42/bengali-movies/default/1.html"            to "Bengali Movies",
        "$mainUrl/category/49/korean-hindi-dubbed-movies/default/1.html" to "Korean Hindi Dubbed",
    )

    // Category page URL থেকে page number change
    private fun getCategoryPage(baseUrl: String, page: Int): String {
        // URL format: /category/ID/name/default/1.html → /category/ID/name/default/PAGE.html
        return if (page == 1) baseUrl
        else baseUrl.replace("/default/1.html", "/default/$page.html")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = getCategoryPage(request.data, page)
        val doc = app.get(url, headers = ua).document
        val items = doc.select("div.update a.ins, div.L a.fileNamee, div.updates a.ins")
            .mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("a:contains(Next), a:contains(»)") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/mobile/search?find=${query.encodeToURL()}&per_page=1",
            headers = ua
        ).document
        return doc.select("div.update a.ins, div.L a, a.ins").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ua).document

        val title = doc.selectFirst("div.Text, h1, title")
            ?.text()?.trim() ?: "Unknown"

        val poster = doc.selectFirst("img[src*=files/images], img[src*=jalshamoviez]")
            ?.attr("src")?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }

        val isSeries = url.contains("web-series", true) ||
                url.contains("webseries", true) ||
                title.contains("S0", true) ||
                title.contains("Season", true)

        // Quality links বের করো — a.fileName এ আছে
        // Pattern: /file/ID/filename.html
        val fileLinks = doc.select("a.fileName[href*=/file/]")

        return if (isSeries || fileLinks.size > 1) {
            val episodes = arrayListOf<Episode>()
            fileLinks.forEachIndexed { i, a ->
                val href = a.attr("abs:href").trim()
                val epTitle = a.selectFirst("div")?.text()?.trim()
                    ?.replace(Regex("Hits:.*"), "")?.trim()
                    ?: "Episode ${i + 1}"
                val quality = when {
                    href.contains("480p") || epTitle.contains("480p") -> "480p"
                    href.contains("720p") || epTitle.contains("720p") -> "720p"
                    href.contains("1080p") || epTitle.contains("1080p") -> "1080p"
                    else -> "HD"
                }
                episodes.add(newEpisode(href) {
                    name = "$quality - $epTitle"
                    season = 1
                    episode = i + 1
                })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
            }
        } else {
            // Single movie — file link সরাসরি data হিসেবে pass করো
            val fileUrl = fileLinks.firstOrNull()?.attr("abs:href") ?: url
            newMovieLoadResponse(title, url, TvType.Movie, fileUrl) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = /file/ID/filename.html
        // Chain: file page → server page → download → CDN redirect

        val fileId = Regex("/file/(\\d+)/").find(data)?.groupValues?.get(1)
            ?: return false

        // Step 1: file page থেকে server link বের করো
        val fileDoc = app.get(data, headers = ua).document
        val serverLink = fileDoc.selectFirst("a.dwnLink[href*=/server/]")
            ?.attr("abs:href") ?: return false

        // Step 2: server page থেকে download link বের করো
        val serverDoc = app.get(serverLink, headers = ua).document
        val downloadPath = serverDoc.selectFirst("a.dwnLink[href*=/download/]")
            ?.attr("href") ?: "/download/$fileId/server_1"
        val downloadUrl = if (downloadPath.startsWith("http")) downloadPath
                          else "$mainUrl$downloadPath"

        // Step 3: HEAD request দিয়ে CDN redirect URL পাও
        val finalUrl = app.get(
            downloadUrl,
            headers = ua + mapOf("Referer" to serverLink),
            allowRedirects = false
        ).headers["location"]
            ?: app.get(downloadUrl, headers = ua + mapOf("Referer" to serverLink)).url

        if (finalUrl.isBlank() || !finalUrl.startsWith("http")) return false

        // Quality বের করো filename থেকে
        val quality = when {
            finalUrl.contains("1080p") || data.contains("1080p") -> Qualities.P1080.value
            finalUrl.contains("720p") || data.contains("720p") -> Qualities.P720.value
            finalUrl.contains("480p") || data.contains("480p") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = finalUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
                this.headers = ua
            }
        )
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("abs:href").ifBlank { return null }
        if (!href.contains("/movie/")) return null

        val title = selectFirst("div b, div, img")?.let {
            it.text().trim().ifBlank { it.attr("alt").trim() }
        }?.ifBlank { return null } ?: return null

        val poster = selectFirst("img[src]")?.attr("src")?.let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }

        val isSeries = href.contains("web-series", true) ||
                href.contains("webseries", true) ||
                title.contains("S0", true) ||
                title.contains("Season", true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    private fun String.encodeToURL(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
