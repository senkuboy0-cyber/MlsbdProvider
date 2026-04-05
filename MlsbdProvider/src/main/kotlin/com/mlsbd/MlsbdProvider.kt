package com.mlsbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MlsbdProvider : MainAPI() {

    override var mainUrl = "https://www.jalshamoviez.lifestyle"
    override var name = "JalshaMoviez"
    override var lang = "bn"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Others,
    )

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/69/bengali-webseries-hd/default/1.html"        to "Bengali Web Series",
        "$mainUrl/category/1/bollywood-movies/default/1.html"              to "Bollywood Movies",
        "$mainUrl/category/5/hollywood-hindi-dubbed-movies/default/1.html" to "Hollywood Hindi Dubbed",
        "$mainUrl/category/3/south-indian-movies-dubbed-in-hindi/default/1.html" to "South Indian Hindi Dubbed",
        "$mainUrl/category/68/web-series-free-download/default/1.html"     to "Web Series",
        "$mainUrl/category/70/hindi-webseries-hd/default/1.html"           to "Hindi Web Series",
        "$mainUrl/category/2/hollywood-movies/default/1.html"              to "Hollywood Movies",
        "$mainUrl/category/42/bengali-movies/default/1.html"               to "Bengali Movies",
        "$mainUrl/category/49/korean-hindi-dubbed-movies/default/1.html"   to "Korean Hindi Dubbed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // URL: /category/ID/name/default/1.html → page 2: /default/2.html
        val url = request.data.replace("/default/1.html", "/default/$page.html")
        val doc = app.get(url, headers = ua).document

        // Category page: div.L > a[href*=/movie/]
        val items = doc.select("div.L a[href*=/movie/]").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
            val title = (a.attr("alt").ifBlank { a.attr("title") }
                .ifBlank { a.selectFirst("b")?.text() }
                ?: return@mapNotNull null).trim()
            val isSeries = title.contains("S0", true) ||
                    title.contains("Season", true) ||
                    title.contains("Series", true) ||
                    title.contains("Webseries", true)
            if (isSeries)
                newTvSeriesSearchResponse(title, href, TvType.TvSeries)
            else
                newMovieSearchResponse(title, href, TvType.Movie)
        }

        val hasNext = doc.selectFirst("a:contains(»), a[href*=default/${page+1}]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get(
            "$mainUrl/mobile/search?find=$encoded&per_page=1",
            headers = ua
        ).document
        return doc.select("div.L a[href*=/movie/], div.update a.ins[href*=/movie/]")
            .mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                val title = (a.attr("alt").ifBlank { a.attr("title") }
                    .ifBlank { a.selectFirst("b, div")?.text() }
                    ?: return@mapNotNull null).trim().ifBlank { return@mapNotNull null }
                val poster = a.selectFirst("img")?.attr("src")?.let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                val isSeries = title.contains("S0", true) ||
                        title.contains("Season", true) || title.contains("Series", true)
                if (isSeries)
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
                else
                    newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ua).document

        val title = doc.selectFirst("div.Text")?.text()?.trim()
            ?: doc.title().replace("Jalshamoviez", "").trim()

        val poster = doc.selectFirst("img[src*=files/images]")?.attr("src")?.let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }

        val isSeries = title.contains("S0", true) ||
                title.contains("Season", true) ||
                title.contains("Series", true) ||
                title.contains("Webseries", true)

        val fileLinks = doc.select("a.fileName[href*=/file/]")

        return if (isSeries || fileLinks.size > 1) {
            val episodes = arrayListOf<Episode>()
            fileLinks.forEachIndexed { i, a ->
                val href = a.attr("abs:href").trim()
                val epText = a.selectFirst("div")?.ownText()?.trim() ?: ""
                val quality = when {
                    epText.contains("1080p") || href.contains("1080p") -> "1080p"
                    epText.contains("720p") || href.contains("720p") -> "720p"
                    epText.contains("480p") || href.contains("480p") -> "480p"
                    else -> "HD"
                }
                episodes.add(newEpisode(href) {
                    name = quality
                    season = 1
                    episode = i + 1
                })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
            }
        } else {
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
        val fileId = Regex("/file/(\\d+)/").find(data)?.groupValues?.get(1)
            ?: return false

        // Step 1: file page → server link
        val fileDoc = app.get(data, headers = ua).document
        val serverLink = fileDoc.selectFirst("a.dwnLink[href*=/server/]")
            ?.attr("abs:href") ?: return false

        // Step 2: server page → download link
        val serverDoc = app.get(serverLink, headers = ua).document
        val downloadPath = serverDoc.selectFirst("a.dwnLink[href*=/download/]")
            ?.attr("href") ?: "/download/$fileId/server_1"
        val downloadUrl = if (downloadPath.startsWith("http")) downloadPath
                          else "$mainUrl$downloadPath"

        // Step 3: GET with redirect → CDN URL
        val response = app.get(
            downloadUrl,
            headers = ua + mapOf("Referer" to serverLink)
        )
        val finalUrl = response.url

        if (finalUrl.isBlank() || !finalUrl.startsWith("http") ||
            finalUrl.contains("jalshamoviez.lifestyle")) return false

        val quality = when {
            data.contains("1080p") || finalUrl.contains("1080p") -> Qualities.P1080.value
            data.contains("720p") || finalUrl.contains("720p") -> Qualities.P720.value
            data.contains("480p") || finalUrl.contains("480p") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }

        callback(
            newExtractorLink(
                source = name,
                name = "$name - $quality",
                url = finalUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
                this.headers = ua
            }
        )
        return true
    }
}
