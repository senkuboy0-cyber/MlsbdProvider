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

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
    )

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
        return doc.select("article, .post-item, .item, .result-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, interceptor = cfKiller).document

        val title = doc.selectFirst("h1.entry-title, h1.post-title, .entry-header h1, h1")
            ?.text()?.trim() ?: "Unknown"

        val poster = doc.selectFirst(
            ".post-thumbnail img, .wp-post-image, img.attachment-post-thumbnail, " +
            ".featured-image img, .entry-content img, figure img, .thumb img"
        )?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.takeIf { it.startsWith("http") }

        val plot = doc.selectFirst(".entry-content p, .post-content p")?.text()?.trim()
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()

        val isSeries = url.contains("series", true) ||
                doc.select("a[rel=category]").any { it.text().contains("series", true) }

        val content = doc.selectFirst(".entry-content, .post-content")

        // FU + FN hidden inputs বের করো — download link chain এর শুরু
        val hiddenLinks = content?.select("a[href]")?.map {
            it.attr("abs:href").trim()
        }?.filter {
            it.contains("technews24") || it.contains("savelinks") ||
            it.contains("sharelink") || it.contains("freethemesy")
        } ?: emptyList()

        // সব download buttons/links collect করো
        val allLinks = (content?.select("a[href]") ?: emptyList<Element>())
            .map { it.attr("abs:href").trim() }
            .filter { it.isNotBlank() && it.startsWith("http") }
            .distinct()

        return if (isSeries) {
            val episodes = arrayListOf<Episode>()
            allLinks.filter {
                it.contains("technews24") || it.contains("savelinks") ||
                it.contains("sharelink") || isVideoHost(it)
            }.forEachIndexed { i, href ->
                episodes.add(newEpisode(href) {
                    name = "Link ${i + 1}"
                    season = 1
                    episode = i + 1
                })
            }
            if (episodes.isEmpty()) {
                allLinks.take(15).forEachIndexed { i, href ->
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
        // technews24 chain bypass
        if (data.contains("technews24") || data.contains("sharelink-3")) {
            return bypassTechnews24Chain(data, subtitleCallback, callback)
        }

        // Direct video host
        if (isVideoHost(data)) {
            loadExtractor(data, mainUrl, subtitleCallback, callback)
            return true
        }

        // Post page থেকে শুরু
        val doc = app.get(data, interceptor = cfKiller).document
        val content = doc.selectFirst(".entry-content, .post-content") ?: return false

        // iframes
        content.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").trim()
            if (src.isNotBlank() && !src.contains("youtube")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // সব links process করো
        content.select("a[href]").forEach { a ->
            val href = a.attr("abs:href").trim()
            if (href.isBlank()) return@forEach
            when {
                href.contains("technews24") || href.contains("sharelink-3") ->
                    bypassTechnews24Chain(href, subtitleCallback, callback)
                isVideoHost(href) ->
                    loadExtractor(href, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // Python code এর get_download_links() এর Kotlin version
    private suspend fun bypassTechnews24Chain(
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Step 1: page থেকে FU, FN বের করো
            val page1 = app.get(pageUrl, headers = defaultHeaders).document
            val fu = page1.selectFirst("input[name=FU]")?.attr("value") ?: return false
            val fn = page1.selectFirst("input[name=FN]")?.attr("value") ?: ""

            // Step 2: technews24 blog.php → FU2
            val page2 = app.post(
                "https://search.technews24.site/blog.php",
                headers = defaultHeaders,
                data = mapOf("FU" to fu, "FN" to fn)
            ).document
            val fu2 = page2.selectFirst("input[name=FU2]")?.attr("value") ?: return false

            // Step 3: freethemesy dld.php → ss + v
            val resp3 = app.post(
                "https://freethemesy.com/dld.php",
                headers = defaultHeaders,
                data = mapOf("FU2" to fu2)
            ).text

            val ss = Regex("var sss = '(.*?)'; var").find(resp3)?.groupValues?.get(1) ?: return false
            val fetchList = Regex("""_0x12fb2a=(.*?);_0x3073""").find(resp3)?.groupValues?.get(1) ?: return false
            // index 18 থেকে v বের করো
            val items = fetchList.trim().removeSurrounding("[", "]")
                .split(Regex(",(?=(?:[^']*'[^']*')*[^']*\$)"))
                .map { it.trim().removeSurrounding("'").removeSurrounding("\"") }
            val v = if (items.size > 18) items[18] else return false

            // Step 4: final API call → final page URL
            val finalHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/123.0.0.0",
                "Referer" to "https://freethemesy.com/dld.php",
                "Origin" to "https://freethemesy.com",
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded"
            )
            val finalPageUrl = app.post(
                "https://freethemesy.com/new/l/api/m",
                headers = finalHeaders,
                data = mapOf("s" to ss, "v" to v)
            ).text.trim()

            if (finalPageUrl.isBlank() || !finalPageUrl.startsWith("http")) return false

            // Step 5: final page থেকে actual links বের করো
            val finalDoc = app.get(finalPageUrl, headers = defaultHeaders).document
            finalDoc.select("a[href]").forEach { a ->
                val href = a.attr("abs:href").trim()
                if (isVideoHost(href)) {
                    loadExtractor(href, finalPageUrl, subtitleCallback, callback)
                }
            }

            // GDrive/direct links ও check করো
            val text = finalDoc.body().text()
            Regex("https?://[\\w./-]+").findAll(text).forEach { match ->
                val url = match.value
                if (isVideoHost(url)) {
                    loadExtractor(url, finalPageUrl, subtitleCallback, callback)
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
            "mixdrop", "voe.sx", "gdflix", "pixeldrain",
            "gofile", "buzzheavier", "krakenfiles", "send.cm",
            "onedrive", "1drv.ms", "terabox", "torrent"
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
        val poster = selectFirst("img.wp-post-image, .post-thumbnail img, figure img, a img, img")
            ?.let { img ->
                img.attr("data-src").ifBlank {
                    img.attr("data-lazy-src").ifBlank { img.attr("src") }
                }
            }?.takeIf { it.startsWith("http") }
        val isSeries = href.contains("series", true)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }
}
