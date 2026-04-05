package com.mlsbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

class MlsbdProvider : MainAPI() {

    override var mainUrl = "https://fojik.site"
    override var name = "Fojik"
    override var lang = "en"
    override val hasMainPage = true

    private val cfKiller = CloudflareKiller()

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Others,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/genre/dual-audio/"          to "Dual Audio",
        "$mainUrl/genre/anime/"               to "Anime",
        "$mainUrl/genre/action/"              to "Action",
        "$mainUrl/genre/bollywood-hindi/"     to "Bollywood Hindi",
        "$mainUrl/genre/english-hollywood/"   to "Hollywood English",
        "$mainUrl/genre/hindi-dubbed/"        to "Hindi Dubbed",
        "$mainUrl/genre/korean/"              to "Korean",
        "$mainUrl/genre/animation-cartoon/"   to "Animation & Cartoon",
        "$mainUrl/genre/drama/"               to "Drama",
        "$mainUrl/genre/thriller/"            to "Thriller",
        "$mainUrl/genre/tv-web-series/"       to "TV & Web Series",
        "$mainUrl/genre/hevc-collection/"     to "HEVC Collection",
        "$mainUrl/genre/tamil/"               to "Tamil",
        "$mainUrl/genre/telugu/"              to "Telugu",
        "$mainUrl/genre/japanese-chinese/"    to "Japanese & Chinese",
        "$mainUrl/genre/dc-marvel-superhero/" to "DC, Marvel & Superhero",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url, interceptor = cfKiller).document
        val items = doc.select("article, .post-item, .item").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("a.next.page-numbers, .nav-links a[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded", interceptor = cfKiller).document
        return doc.select("article, .post-item, .item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, interceptor = cfKiller).document

        val title = doc.selectFirst("h1.entry-title, h1.post-title, .entry-header h1, h1")
            ?.text()?.trim() ?: "Unknown"

        val poster = doc.selectFirst(
            ".post-thumbnail img, .wp-post-image, " +
            "img.attachment-post-thumbnail, .featured-image img, " +
            ".entry-content img, figure img, .thumb img"
        )?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.takeIf { it.startsWith("http") }

        val plot = doc.selectFirst(
            ".entry-content p, .post-content p, .description p"
        )?.text()?.trim()

        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()

        val isSeries = url.contains("tv-web-series", true) ||
                url.contains("series", true) ||
                doc.select("a[rel=category]").any {
                    it.text().contains("series", true)
                }

        val content = doc.selectFirst(".entry-content, .post-content")

        // সব links collect করো
        val allLinks = content?.select("a[href]")?.map {
            it.attr("abs:href").trim()
        }?.filter { it.isNotBlank() && it.startsWith("http") } ?: emptyList()

        return if (isSeries) {
            val episodes = arrayListOf<Episode>()
            allLinks.filter { isVideoHost(it) || it.contains("savelinks") }
                .forEachIndexed { i, href ->
                    episodes.add(newEpisode(href) {
                        name = "Episode ${i + 1}"
                        season = 1
                        episode = i + 1
                    })
                }
            // fallback — সব links episode হিসেবে দাও
            if (episodes.isEmpty()) {
                allLinks.take(20).forEachIndexed { i, href ->
                    episodes.add(newEpisode(href) {
                        name = "Link ${i + 1}"
                        season = 1
                        episode = i + 1
                    })
                }
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
        // savelinks.me bypass
        if (data.contains("savelinks")) {
            return tryBypassSavelinks(data, subtitleCallback, callback)
        }

        // Direct video hosts
        if (isVideoHost(data)) {
            loadExtractor(data, mainUrl, subtitleCallback, callback)
            return true
        }

        // Post page — সব links বের করো
        val doc = app.get(data, interceptor = cfKiller).document
        val content = doc.selectFirst(".entry-content, .post-content") ?: return false

        // iframes
        content.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").trim()
            if (src.isNotBlank() && !src.contains("youtube")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // সব a[href] চেক করো
        content.select("a[href]").forEach { a ->
            val href = a.attr("abs:href").trim()
            if (href.isBlank()) return@forEach
            when {
                href.contains("savelinks") -> tryBypassSavelinks(href, subtitleCallback, callback)
                isVideoHost(href) -> loadExtractor(href, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // savelinks.me bypass
    private suspend fun tryBypassSavelinks(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(url, interceptor = cfKiller).document
            // savelinks page এ actual links থাকে
            doc.select("a[href]").forEach { a ->
                val href = a.attr("abs:href").trim()
                if (isVideoHost(href)) {
                    loadExtractor(href, url, subtitleCallback, callback)
                }
            }
            // div বা p এর ভেতরে text link ও থাকতে পারে
            doc.select("div, p, td").forEach { el ->
                val text = el.text().trim()
                if (text.startsWith("https://") && isVideoHost(text)) {
                    loadExtractor(text, url, subtitleCallback, callback)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun isVideoHost(url: String): Boolean {
        val hosts = listOf(
            "drive.google", "mega.nz", "mediafire", "hubcloud",
            "gdtot", "streamtape", "doodstream", "filemoon",
            "mixdrop", "upstream", "voe.sx", "gdflix",
            "pixeldrain", "1drv.ms", "gofile", "buzzheavier",
            "krakenfiles", "send.cm", "onedrive", "terabox",
        )
        return hosts.any { url.contains(it, ignoreCase = true) }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst(
            "h2.entry-title a, h3.entry-title a, .entry-title a, " +
            ".post-title a, h2 a, h3 a, .title a"
        ) ?: return null
        val title = a.text().trim().ifBlank { return null }
        val href = a.attr("abs:href").ifBlank { return null }

        val poster = selectFirst(
            "img.wp-post-image, .post-thumbnail img, " +
            "figure img, .thumb img, a img, img"
        )?.let { img ->
            img.attr("data-src").ifBlank {
                img.attr("data-lazy-src").ifBlank { img.attr("src") }
            }
        }?.takeIf { it.startsWith("http") }

        val isSeries = href.contains("tv-web-series", true) ||
                href.contains("series", true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }
}
