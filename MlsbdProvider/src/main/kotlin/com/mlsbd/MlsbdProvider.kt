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
        "$mainUrl/category/227/2026-latest-bengali-movies/default/1.html"         to "🆕 Bengali Movies 2026",
        "$mainUrl/category/228/2026-latest-bollywood-movies/default/1.html"       to "🆕 Bollywood 2026",
        "$mainUrl/category/230/2026-latest-hollywood-hindi-dubbed-movies/default/1.html" to "🆕 Hollywood Hindi 2026",
        "$mainUrl/category/229/2026-latest-south-indian-hindi-dubbed-movies/default/1.html" to "🆕 South Indian 2026",
        "$mainUrl/category/69/bengali-webseries-hd/default/1.html"                to "Bengali Web Series",
        "$mainUrl/category/70/hindi-webseries-hd/default/1.html"                  to "Hindi Web Series",
        "$mainUrl/category/163/korean-hindi-dubbed-series/default/1.html"         to "Korean Hindi Series",
        "$mainUrl/category/15/bengali-movies/default/1.html"                      to "Bengali Movies",
        "$mainUrl/category/14/bollywood-movies/default/1.html"                    to "Bollywood Movies",
        "$mainUrl/category/12/hollywood-hindi-dubbed-movies/default/1.html"       to "Hollywood Hindi Dubbed",
        "$mainUrl/category/33/hollywood-movies/default/1.html"                    to "Hollywood Movies",
        "$mainUrl/category/13/south-indian-movies-dubbed-in-hindi/default/1.html" to "South Indian Hindi Dubbed",
        "$mainUrl/category/137/korean-hindi-dubbed-movies/default/1.html"         to "Korean Hindi Dubbed Movies",
        "$mainUrl/category/140/hollywood-bengali-dubbed-movies/default/1.html"    to "Hollywood Bengali Dubbed",
        "$mainUrl/category/100/marvel-cinematic-universe-movies/default/1.html"   to "Marvel Movies",
        "$mainUrl/category/68/web-series-free-download/default/1.html"            to "All Web Series",
        "$mainUrl/category/104/hot-short-films/default/1.html"                    to "Short Films",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.replace("/default/1.html", "/default/$page.html")
        val doc = app.get(url, headers = ua).document
        val items = doc.select("div.L a[href*=/movie/]").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
            val title = (a.attr("alt").ifBlank { a.attr("title") }
                .ifBlank { a.selectFirst("b")?.text() }
                ?: return@mapNotNull null).trim()
            if (title.isBlank()) return@mapNotNull null

            // Poster: URL থেকে title বের করে image URL বানাও
            val movieId = Regex("/movie/(\\d+)/").find(href)?.groupValues?.get(1)
            val imgName = href.substringAfterLast("/")
                .removeSuffix(".html")
                .split("-")
                .joinToString("_") { it.replaceFirstChar { c -> c.uppercaseChar() } }
            val poster = "$mainUrl/files/images/$imgName.jpg"

            val isSeries = title.contains("S0", true) || title.contains("Season", true) ||
                    title.contains("Series", true) || title.contains("Webseries", true)
            if (isSeries)
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
            else
                newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
        val hasNext = doc.selectFirst("a[href*=default/${page + 1}]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/mobile/search?find=$encoded&per_page=1", headers = ua).document
        return doc.select("div.L a[href*=/movie/], div.update a.ins[href*=/movie/]")
            .mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                val title = (a.attr("alt").ifBlank { a.attr("title") }
                    .ifBlank { a.selectFirst("b, div")?.text() }
                    ?: return@mapNotNull null).trim().ifBlank { return@mapNotNull null }
                val poster = a.selectFirst("img[src*=files/images]")?.attr("abs:src")
                val isSeries = title.contains("S0", true) || title.contains("Season", true) ||
                        title.contains("Series", true)
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

        // Poster — files/images থেকে সরাসরি নাও
        val poster = doc.selectFirst("img[src*=files/images]")?.attr("src")?.let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }

        val isSeries = title.contains("S0", true) || title.contains("Season", true) ||
                title.contains("Series", true) || title.contains("Webseries", true) ||
                url.contains("web-series", true) || url.contains("webseries", true)

        // সব quality links — 480p, 720p, 1080p আলাদা আলাদা
        val fileLinks = doc.select("a.fileName[href*=/file/]")

        return if (isSeries || fileLinks.size > 1) {
            val episodes = arrayListOf<Episode>()
            fileLinks.forEachIndexed { i, a ->
                val href = a.attr("abs:href").trim()
                val epText = a.text().trim()
                // Quality detect করো
                val quality = when {
                    epText.contains("1080p", true) || href.contains("1080p") -> "1080p"
                    epText.contains("720p", true) || href.contains("720p") -> "720p"
                    epText.contains("480p", true) || href.contains("480p") -> "480p"
                    epText.contains("4k", true) || href.contains("2160p") -> "4K"
                    else -> "HD"
                }
                // Size info
                val size = Regex("Size: ([\\d.]+ [GM]B)").find(epText)?.groupValues?.get(1) ?: ""
                val epName = if (size.isNotBlank()) "$quality [$size]" else quality

                episodes.add(newEpisode(href) {
                    name = epName
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

        // Step 3: GET → CDN redirect URL
        val response = app.get(
            downloadUrl,
            headers = ua + mapOf("Referer" to serverLink)
        )
        val finalUrl = response.url

        if (finalUrl.isBlank() || !finalUrl.startsWith("http") ||
            finalUrl.contains("jalshamoviez")) return false

        val quality = when {
            data.contains("1080p") || finalUrl.contains("1080p") -> Qualities.P1080.value
            data.contains("720p") || finalUrl.contains("720p") -> Qualities.P720.value
            data.contains("480p") || finalUrl.contains("480p") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }

        val qualityName = when (quality) {
            Qualities.P1080.value -> "1080p"
            Qualities.P720.value -> "720p"
            Qualities.P480.value -> "480p"
            else -> "HD"
        }

        callback(
            newExtractorLink(
                source = name,
                name = "$name [$qualityName]",
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
