package com.mlsbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MlsbdProvider : MainAPI() {

    override var mainUrl = "https://mlsbd.co"
    override var name = "MLSBD"
    override var lang = "bn"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Others,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/bengali-movies/"      to "Bengali Movies",
        "$mainUrl/category/bangla-dubbed/"       to "Bangla Dubbed",
        "$mainUrl/category/hollywood-movies/"    to "Hollywood Movies",
        "$mainUrl/category/bollywood-movies/"    to "Bollywood Movies",
        "$mainUrl/category/hindi-dubbed-movies/" to "Hindi Dubbed",
        "$mainUrl/category/south-indian-movies/" to "South Indian",
        "$mainUrl/category/korean-moviesdrama/"  to "Korean Drama",
        "$mainUrl/category/anime/"               to "Anime",
        "$mainUrl/category/cartoon-series/"      to "Cartoon",
        "$mainUrl/category/web-series/"          to "Web Series",
        "$mainUrl/category/tv-series/"           to "TV Series",
        "$mainUrl/category/tamil-movies/"        to "Tamil Movies",
        "$mainUrl/category/telugu-movies/"       to "Telugu Movies",
        "$mainUrl/category/4k-uhd/"              to "4K UHD",
        "$mainUrl/category/1080p/"               to "1080p",
        "$mainUrl/category/hoichoi-originals/"   to "Hoichoi Originals",
        "$mainUrl/category/natok-teleflim/"      to "Natok & Telefilm",
    )

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
        "Referer" to mainUrl,
    )

    private suspend fun fetchDoc(url: String): Document {
        val res: NiceResponse = app.get(url, headers = ua)
        return Jsoup.parse(res.body.string())
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = fetchDoc(url)
        val items = doc.select("article").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("a.next.page-numbers") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = fetchDoc("$mainUrl/?s=$encoded")
        return doc.select("article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDoc(url)
        val title = doc.selectFirst("h1.entry-title, h1.post-title, .entry-header h1")
            ?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst(
            "div.post-thumbnail img, .wp-post-image, img.attachment-post-thumbnail"
        )?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.takeIf { it.startsWith("http") }
        val plot = doc.selectFirst(".entry-content p")?.text()?.trim()
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
        val isSeries = url.contains("web-series", true) || url.contains("tv-series", true)
        val entryContent = doc.selectFirst(".entry-content, .post-content")
        return if (isSeries) {
            val episodes = arrayListOf<Episode>()
            val linkEls = entryContent?.select("a[href]")?.filter { a ->
                val h = a.attr("abs:href")
                h.contains("drive.google") || h.contains("mega.nz") ||
                h.contains("mediafire") || h.contains("hubcloud") ||
                h.contains("gdtot") || h.contains("streamtape")
            } ?: emptyList()
            linkEls.forEachIndexed { i, a ->
                episodes.add(newEpisode(a.attr("abs:href")) {
                    name = a.text().ifBlank { "Episode ${i + 1}" }
                    season = 1
                    episode = i + 1
                })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val directHosts = listOf("drive.google", "mega.nz", "mediafire", "hubcloud",
            "gdtot", "streamtape", "doodstream", "filemoon")
        if (directHosts.any { data.contains(it) }) {
            loadExtractor(data, mainUrl, subtitleCallback, callback)
            return true
        }
        val doc = fetchDoc(data)
        val content = doc.selectFirst(".entry-content, .post-content") ?: return false
        content.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").trim()
            if (src.isNotBlank() && !src.contains("youtube")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        listOf("a[href*=drive.google.com]", "a[href*=mega.nz]", "a[href*=mediafire.com]",
            "a[href*=hubcloud]", "a[href*=streamtape]", "a[href*=doodstream]",
            "a[href*=filemoon]", "a[href*=mixdrop]").forEach { sel ->
            content.select(sel).forEach { a ->
                val href = a.attr("abs:href").trim()
                if (href.isNotBlank()) loadExtractor(href, data, subtitleCallback, callback)
            }
        }
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("h2.entry-title a, h3.entry-title a, .entry-title a, h2 a, h3 a")
            ?: return null
        val title = a.text().trim().ifBlank { return null }
        val href = a.attr("abs:href").ifBlank { return null }
        val poster = selectFirst("img.wp-post-image, .post-thumbnail img, figure img, a img, img")
            ?.let { img ->
                img.attr("data-src").ifBlank {
                    img.attr("data-lazy-src").ifBlank { img.attr("src") }
                }
            }?.takeIf { it.startsWith("http") }
        val isSeries = href.contains("web-series", true) || href.contains("tv-series", true)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }
}
