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
        "$mainUrl/category/137/korean-hindi-dubbed-movies/default/1.html"         to "Korean Hindi Dubbed",
        "$mainUrl/category/140/hollywood-bengali-dubbed-movies/default/1.html"    to "Hollywood Bengali Dubbed",
        "$mainUrl/category/100/marvel-cinematic-universe-movies/default/1.html"   to "Marvel Movies",
        "$mainUrl/category/68/web-series-free-download/default/1.html"            to "All Web Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.replace("/default/1.html", "/default/$page.html")
        val doc = app.get(url, headers = ua).document
        val items = doc.select("div.L a[href*=/movie/]").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("a[href*=default/${page + 1}]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/mobile/search?find=$encoded&per_page=1", headers = ua).document
        return doc.select("div.L a[href*=/movie/], div.update a.ins[href*=/movie/]")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ua).document

        val title = doc.selectFirst("div.Text")?.text()?.trim()
            ?: doc.title().replace("Jalshamoviez", "").trim()

        val poster = doc.selectFirst("img[src*=files/images]")?.attr("src")?.let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }

        // সব file links collect করো
        val fileLinks = doc.select("a.fileName[href*=/file/]")

        // Series check — title দেখে বুঝবো
        val isSeries = title.contains("S0", true) ||
                title.contains("Season", true) ||
                title.contains("Series", true) ||
                title.contains("Webseries", true) ||
                url.contains("web-series", true) ||
                url.contains("webseries", true)

        return if (isSeries) {
            // Series এ: group by episode number
            // প্রতিটা episode এর ৩টা quality থাকে
            // Episode grouping: 480p/720p/1080p একই episode এর different quality
            
            // File text দেখে episode গুলো group করো
            data class EpisodeGroup(
                val epNum: String,
                val links: MutableList<String> = mutableListOf()
            )
            
            val episodeMap = linkedMapOf<String, MutableList<String>>()
            
            fileLinks.forEach { a ->
                val href = a.attr("abs:href").trim()
                val text = a.text().trim()
                
                // Episode number বের করো: ep01, ep02, episode-1 etc
                val epNum = Regex("""(?:ep|episode[-.]?)(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href + text)?.groupValues?.get(1)
                    ?: Regex("""s\d+e(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)
                
                if (epNum != null) {
                    episodeMap.getOrPut(epNum) { mutableListOf() }.add(href)
                } else {
                    // Episode number নেই — quality দেখে group করো
                    val quality = when {
                        text.contains("480p") || href.contains("480p") -> "480"
                        text.contains("720p") || href.contains("720p") -> "720"
                        text.contains("1080p") || href.contains("1080p") -> "1080"
                        else -> "hd"
                    }
                    episodeMap.getOrPut(quality) { mutableListOf() }.add(href)
                }
            }
            
            val episodes = arrayListOf<Episode>()
            
            if (episodeMap.size > 1 && episodeMap.keys.all { it.toIntOrNull() != null }) {
                // Real episodes found
                episodeMap.entries.forEachIndexed { idx, (epNum, links) ->
                    // সব quality links একসাথে pipe দিয়ে join করো
                    val data = links.joinToString("|")
                    episodes.add(newEpisode(data) {
                        name = "Episode $epNum"
                        season = 1
                        episode = epNum.toIntOrNull() ?: (idx + 1)
                    })
                }
            } else {
                // Batch download — সব links একটা episode এ
                val allLinks = fileLinks.map { it.attr("abs:href") }.joinToString("|")
                episodes.add(newEpisode(allLinks) {
                    name = "Complete Series"
                    season = 1
                    episode = 1
                })
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
            }
        } else {
            // Movie — সব quality links একসাথে data হিসেবে pass করো
            val allLinks = fileLinks.map { it.attr("abs:href") }.joinToString("|")
            newMovieLoadResponse(title, url, TvType.Movie, allLinks) {
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
        // data = pipe-separated file URLs
        val fileUrls = data.split("|").filter { it.isNotBlank() }
        
        var found = false
        fileUrls.forEach { fileUrl ->
            val success = processFileLink(fileUrl, subtitleCallback, callback)
            if (success) found = true
        }
        return found
    }

    private suspend fun processFileLink(
        fileUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val fileId = Regex("/file/(\\d+)/").find(fileUrl)?.groupValues?.get(1)
                ?: return false

            // Step 1: file page → server link
            val fileDoc = app.get(fileUrl, headers = ua).document
            val serverLink = fileDoc.selectFirst("a.dwnLink[href*=/server/]")
                ?.attr("abs:href") ?: return false

            // Step 2: server page → download link
            val serverDoc = app.get(serverLink, headers = ua).document
            val downloadPath = serverDoc.selectFirst("a.dwnLink[href*=/download/]")
                ?.attr("href") ?: "/download/$fileId/server_1"
            val downloadUrl = if (downloadPath.startsWith("http")) downloadPath
                              else "$mainUrl$downloadPath"

            // Step 3: GET → CDN redirect
            val response = app.get(downloadUrl, headers = ua + mapOf("Referer" to serverLink))
            val finalUrl = response.url

            if (finalUrl.isBlank() || !finalUrl.startsWith("http") ||
                finalUrl.contains("jalshamoviez")) return false

            // Quality detect
            val qualityVal = when {
                fileUrl.contains("1080p") || finalUrl.contains("1080p") -> Qualities.P1080.value
                fileUrl.contains("720p") || finalUrl.contains("720p") -> Qualities.P720.value
                fileUrl.contains("480p") || finalUrl.contains("480p") -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }
            val qualityName = when (qualityVal) {
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
                    this.quality = qualityVal
                    this.headers = ua
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("abs:href").ifBlank { return null }
        if (!href.contains("/movie/")) return null
        val title = (attr("alt").ifBlank { attr("title") }
            .ifBlank { selectFirst("b")?.text() }
            ?: return null).trim().ifBlank { return null }
        val poster = selectFirst("img[src*=files/images]")?.attr("abs:src")
        val isSeries = title.contains("S0", true) || title.contains("Season", true) ||
                title.contains("Series", true) || title.contains("Webseries", true)
        return if (isSeries)
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        else
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }
}
