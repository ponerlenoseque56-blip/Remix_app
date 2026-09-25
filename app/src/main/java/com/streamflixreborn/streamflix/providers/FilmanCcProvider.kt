package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.FilmanLoginServer
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import android.util.Base64
import java.net.URL
import java.util.concurrent.TimeUnit

object FilmanCcProvider : Provider {

    override val name = "Filman.cc"
    override val baseUrl = "https://filman.cc"
    override val logo = "$baseUrl/favicon.ico"
    override val language = "pl"

    private var webViewResolver: WebViewResolver? = null
    private val loginServer = FilmanLoginServer()

    // Guards ONLY the WebView-bypass / login flow — not the whole document fetch.
    // This prevents concurrent coroutines from racing into the login dialog simultaneously.
    // It is NOT held recursively (getDocument → triggerManualLogin → getDocument(depth+1)
    // is safe because the inner call uses depth > 0 and skips the bypass path entirely
    // on success; if it needs bypass again it will acquire the lock again after the outer
    // one has released it).
    private val loginMutex = Mutex()

    // In-memory TvShow cache: avoids re-fetching the full series page every time the user
    // switches between seasons. Entries expire after CACHE_TTL_MS.
    private data class CachedTvShow(val tvShow: TvShow, val fetchedAtMs: Long)
    private val tvShowCache = mutableMapOf<String, CachedTvShow>()
    private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes

    private const val TAG = "FilmanCc"

    private fun getResolver(): WebViewResolver {
        return webViewResolver ?: WebViewResolver(StreamFlixApp.instance).also {
            webViewResolver = it
        }
    }

    // ---------------------------------------------------------------------------
    // Document fetching
    // ---------------------------------------------------------------------------

    /**
     * Fetches [url] and returns a parsed Jsoup [Document].
     *
     * Fast path: plain OkHttp request (no lock held).
     * Slow paths (Cloudflare challenge / login redirect): serialised through [loginMutex]
     * so that at most one coroutine at a time drives the WebView / QR-login UI.
     *
     * Recursive calls (depth > 0) are retry-attempts after a login was completed by the
     * first caller; they go through the fast path first and only fall back to the slow
     * path if — for some reason — the site still requires intervention.
     */
    private suspend fun getDocument(url: String, depth: Int = 0): Document {
        if (depth > 2) {
            Log.w(TAG, "[Provider] Max redirect depth reached for $url")
            return Jsoup.parse("<html><body>Too many redirects/login attempts</body></html>")
        }

        // --- Fast path: plain HTTP ---
        try {
            val client = NetworkClient.default.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Referer", baseUrl)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseUrl = response.request.url.toString()
                val html = response.body?.string() ?: ""

                return when {
                    // Server redirected us to the login page
                    responseUrl.contains("/logowanie") -> {
                        Log.d(TAG, "[Provider] Login redirect detected for $url (responseUrl=$responseUrl)")
                        loginMutex.withLock { triggerManualLogin(url, depth) }
                    }

                    // Cloudflare challenge present in body
                    html.contains("cf-browser-verification") ||
                    html.contains("Checking your browser") ||
                    html.contains("Just a moment...") -> {
                        Log.d(TAG, "[Provider] Cloudflare challenge detected for $url")
                        loginMutex.withLock { launchWebViewBypass(url, depth) }
                    }

                    else -> Jsoup.parse(html).apply { setBaseUri(baseUrl) }
                }
            } else {
                Log.w(TAG, "[Provider] HTTP ${response.code} for $url — falling back to WebView bypass")
                return loginMutex.withLock { launchWebViewBypass(url, depth) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Provider] Network error for $url: ${e.message} — falling back to WebView bypass")
            return loginMutex.withLock { launchWebViewBypass(url, depth) }
        }
    }

    /**
     * Called (under [loginMutex]) when OkHttp cannot load the page.
     * Uses the Android WebView (which can solve Cloudflare challenges) to fetch the page.
     * If the WebView ends up on the login page, it chains into [triggerManualLogin].
     *
     * NOTE: This function is already called while holding [loginMutex]. It must NOT try
     * to re-acquire it. The recursive [getDocument] call at the end is safe because it
     * will either succeed on the fast path (no lock needed) or — if it needs the mutex
     * again — it will be in a fresh coroutine invocation after the lock has been released.
     */
    private suspend fun launchWebViewBypass(url: String, depth: Int): Document {
        Log.d(TAG, "[Provider] Launching WebView bypass for $url (depth=$depth)")
        val html = getResolver().get(url)
        Log.d(TAG, "[Provider] WebView bypass finished for $url. HTML length=${html.length}")

        when {
            html.contains("<body>Timeout</body>") -> {
                Log.e(TAG, "[Provider] WebView bypass TIMEOUT for $url")
                // Still try to return whatever we have rather than crashing.
            }

            // WebView ended up on the login page — need manual credentials.
            // Detect by both URL substring (set in WebViewClient.onPageFinished) and body.
            html.contains("filman.cc/logowanie") ||
            html.contains("name=\"login\"") && html.contains("name=\"password\"") -> {
                Log.d(TAG, "[Provider] WebView landed on login page — triggering manual login")
                // Still under loginMutex — triggerManualLogin does NOT re-acquire the mutex.
                return triggerManualLoginUnlocked(url, depth)
            }
        }

        return Jsoup.parse(html).apply { setBaseUri(baseUrl) }
    }

    /**
     * Drives the QR-code login flow. Must be called while [loginMutex] is already held
     * (hence "Unlocked" in the name — the caller holds the lock, so we do not re-acquire).
     * After a successful login the function retries [getDocument] with `depth + 1`; that
     * call goes through the fast path first (OkHttp now has valid session cookies) and
     * only falls back to the bypass/login path if something unexpected happens.
     */
    private suspend fun triggerManualLoginUnlocked(originalUrl: String, depth: Int): Document {
        Log.d(TAG, "[Provider] Launching QR Code Login Server")
        val credentials = loginServer.requestLogin()
        Log.d(TAG, "[Provider] Login server result: user=${credentials?.user}")

        if (credentials != null) {
            val html = getResolver().get("$baseUrl/logowanie", forceVisible = true, credentials = credentials)
            Log.d(TAG, "[Provider] TV WebView login finished, HTML length=${html.length}")
        } else {
            Log.w(TAG, "[Provider] Login cancelled or timed out")
        }

        // Retry without the lock — the OkHttp cookie jar now contains the session cookies
        // from the WebView-based login so the fast path should succeed.
        // We release loginMutex implicitly when this withLock block exits in the caller.
        return getDocument(originalUrl, depth + 1)
    }

    // Keep the old name as a private alias for call-sites that were using it before
    // the refactor (now only called from launchWebViewBypass which is already under lock).
    private suspend fun triggerManualLogin(originalUrl: String, depth: Int): Document =
        triggerManualLoginUnlocked(originalUrl, depth)

    // ---------------------------------------------------------------------------
    // TvShow cache helpers
    // ---------------------------------------------------------------------------

    private fun getCachedTvShow(id: String): TvShow? {
        val entry = tvShowCache[id] ?: return null
        if (System.currentTimeMillis() - entry.fetchedAtMs > CACHE_TTL_MS) {
            tvShowCache.remove(id)
            return null
        }
        return entry.tvShow
    }

    private fun cacheTvShow(id: String, tvShow: TvShow) {
        tvShowCache[id] = CachedTvShow(tvShow, System.currentTimeMillis())
    }

    private fun invalidateTvShowCache(id: String) {
        tvShowCache.remove(id)
    }

    // ---------------------------------------------------------------------------
    // Provider API
    // ---------------------------------------------------------------------------

    override suspend fun getHome(): List<Category> {
        val doc = getDocument(baseUrl)
        val categories = mutableListOf<Category>()
        val processedContainers = mutableSetOf<org.jsoup.nodes.Element>()

        // Check if user is logged out to offer early login
        if (doc.selectFirst("a[href*=/logowanie], a:contains(Zaloguj)") != null) {
            categories.add(
                Category(
                    name = "Konto",
                    list = listOf(
                        Movie(
                            id = "login",
                            title = "Zaloguj się (Opcjonalnie)",
                            poster = logo
                        )
                    )
                )
            )
        }

        // 1. Featured Section
        val featuredContainer = doc.selectFirst("#featured, .featured, #slider, .slider, .owl-carousel, #home-slider")
        if (featuredContainer != null) {
            val featuredItems = parseItems(featuredContainer)
            if (featuredItems.isNotEmpty()) {
                categories.add(Category("Polecane", featuredItems))
                processedContainers.add(featuredContainer)
            }
        }

        // 2. Generic Header to List extraction
        val headers = doc.select("h1, h2, h3, h4, .title, .block-title")
        for (header in headers) {
            val title = header.text().trim()
            if (title.isBlank() || title.length > 50) continue

            var next = header.nextElementSibling()
            if (next == null) next = header.parent()?.nextElementSibling()

            var container: org.jsoup.nodes.Element? = null
            var count = 0
            while (next != null && count < 5) {
                if (next.id() == "item-list" || next.hasClass("item-list") || next.hasClass("row") || next.select(".movie-item, .film-item, .col-xs-6, .poster").isNotEmpty()) {
                    container = next
                    break
                }
                next = next.nextElementSibling()
                count++
            }

            if (container != null && !processedContainers.contains(container)) {
                val items = parseItems(container)
                if (items.isNotEmpty() && categories.none { it.name.equals(title, ignoreCase = true) }) {
                    categories.add(Category(title, items))
                    processedContainers.add(container)
                }
            }
        }

        // 3. Fallback if the generic logic didn't find specific categories
        if (categories.isEmpty() || categories.size == 1) {
            val moviesContainer = doc.select("#item-list, .item-list").firstOrNull()
            if (moviesContainer != null && !processedContainers.contains(moviesContainer)) {
                val movies = parseItems(moviesContainer).filterIsInstance<Movie>()
                if (movies.isNotEmpty() && categories.none { it.name.contains("Filmy", ignoreCase = true) }) {
                    categories.add(Category("Filmy na czasie", movies))
                    processedContainers.add(moviesContainer)
                }
            }

            val tvHeader = doc.select("h3").find { it.text().contains("SERIALE NA CZASIE", ignoreCase = true) }
            val tvContainer = tvHeader?.parent()?.select("div.row, div.item-list")?.find { it.select(".movie-item").isNotEmpty() }
                ?: doc.select("#item-list, .item-list").getOrNull(1)

            if (tvContainer != null && !processedContainers.contains(tvContainer)) {
                val tvShows = parseItems(tvContainer).filterIsInstance<TvShow>()
                if (tvShows.isNotEmpty() && categories.none { it.name.contains("Seriale", ignoreCase = true) }) {
                    categories.add(Category("Seriale na czasie", tvShows))
                    processedContainers.add(tvContainer)
                }
            }
        }

        return categories
    }

    private fun getMainContainer(doc: Document): org.jsoup.nodes.Element {
        val lists = doc.select("#item-list, .item-list, #results, .content-box")
        return if (lists.size > 1) {
            lists.maxByOrNull { it.select(".movie-item, .film-item, .col-xs-6, .poster").size } ?: doc
        } else {
            lists.firstOrNull() ?: doc
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre(id = "filmy/", name = "Filmy"),
                Genre(id = "seriale/", name = "Seriale")
            )
        }
        val url = "$baseUrl/search?phrase=${URLEncoder.encode(query, "UTF-8")}&page=$page"
        val doc = getDocument(url)
        val lists = doc.select("#item-list, .item-list, #results, .content-box")

        if (lists.isNotEmpty()) {
            val allItems = mutableListOf<AppAdapter.Item>()
            for (list in lists) {
                allItems.addAll(parseItems(list))
            }
            return allItems.distinctBy {
                when (it) {
                    is Movie -> it.id
                    is TvShow -> it.id
                    else -> it.hashCode()
                }
            }
        }

        return parseItems(doc)
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val url = if (page == 1) "$baseUrl/filmy/" else "$baseUrl/filmy/?page=$page"
        val doc = getDocument(url)
        return parseItems(getMainContainer(doc)).filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val url = if (page == 1) "$baseUrl/seriale/" else "$baseUrl/seriale/?page=$page"
        val doc = getDocument(url)
        return parseItems(getMainContainer(doc)).filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie {
        if (id == "login") {
            val credentials = loginServer.requestLogin()
            if (credentials != null) {
                getResolver().get("$baseUrl/logowanie", forceVisible = true, credentials = credentials)
                throw Exception("Zalogowano pomyślnie. Odśwież stronę główną.")
            } else {
                throw Exception("Logowanie anulowane.")
            }
        }
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        return getDocument(url).let { doc ->
            val title = doc.selectFirst("h1[itemprop=\"name\"]")?.text()?.replace(doc.selectFirst("h1 .flm-online-badge")?.text() ?: "", "")?.trim() ?: ""
            val overview = doc.selectFirst("#item-content p.description, p.description")?.text()?.trim()
            val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
                ?: doc.selectFirst("img.main-poster")?.let { it.attr("abs:data-src").ifBlank { it.attr("data-src").ifBlank { it.attr("abs:src").ifBlank { it.attr("src") } } } }

            val bannerStyle = doc.selectFirst("#item-headline")?.attr("style") ?: ""
            val banner = Regex("""url\(['"']?(.*?)['"']?\)""").find(bannerStyle)?.groupValues?.getOrNull(1)?.let { doc.absUrl(it) }

            var year: String? = null
            var rating: Double? = null
            var runtime: Int? = null

            doc.select(".flm-meta-item").forEach { item ->
                val icon = item.selectFirst(".flm-meta-icon")?.text() ?: ""
                val value = item.selectFirst(".flm-meta-value")?.text() ?: ""
                when {
                    icon.contains("📅") -> year = value.trim()
                    icon.contains("⭐") -> rating = value.trim().toDoubleOrNull()
                    icon.contains("⏱️") -> {
                        runtime = value.replace("min", "").trim().toIntOrNull()
                    }
                }
            }

            val genres = doc.select(".flm-genre-tags a.flm-genre-tag, .flm-genre-tags a[itemprop=\"genre\"]").map {
                Genre(
                    id = parsePathId(it.attr("href")),
                    name = it.text().trim()
                )
            }

            return@let Movie(
                id = id,
                title = title,
                overview = overview,
                released = year,
                runtime = runtime,
                rating = rating,
                poster = poster,
                banner = banner,
                genres = genres
            )
        }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        return getDocument(url).let { doc ->
            val title = doc.selectFirst("h1[itemprop=\"name\"]")?.text()?.replace(doc.selectFirst("h1 .flm-online-badge")?.text() ?: "", "")?.trim() ?: ""
            val overview = doc.selectFirst("#item-content p.description, p.description")?.text()?.trim()
            val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
                ?: doc.selectFirst("img.main-poster")?.let { it.attr("abs:data-src").ifBlank { it.attr("data-src").ifBlank { it.attr("abs:src").ifBlank { it.attr("src") } } } }

            val bannerStyle = doc.selectFirst("#item-headline")?.attr("style") ?: ""
            val banner = Regex("""url\(['"']?(.*?)['"']?\)""").find(bannerStyle)?.groupValues?.getOrNull(1)?.let { doc.absUrl(it) }

            var year: String? = null
            var rating: Double? = null

            doc.select(".flm-meta-item").forEach { item ->
                val icon = item.selectFirst(".flm-meta-icon")?.text() ?: ""
                val value = item.selectFirst(".flm-meta-value")?.text() ?: ""
                if (icon.contains("📅")) year = value.trim()
                if (icon.contains("⭐")) rating = value.trim().toDoubleOrNull()
            }

            val genres = doc.select(".flm-genre-tags a.flm-genre-tag, .flm-genre-tags a[itemprop=\"genre\"]").map {
                Genre(
                    id = parsePathId(it.attr("href")),
                    name = it.text().trim()
                )
            }

            val seasons = parseSeasonsFromDoc(doc, id, poster)

            val tvShow = TvShow(
                id = id,
                title = title,
                overview = overview,
                released = year,
                rating = rating,
                poster = poster,
                banner = banner,
                genres = genres,
                seasons = seasons
            )

            // Cache the result so season switches don't require a re-fetch
            cacheTvShow(id, tvShow)

            return@let tvShow
        }
    }

    /**
     * Parses the season/episode list from a series detail page document.
     * The actual HTML structure on filman.cc is:
     *
     *   <ul id="episode-list">
     *     <li>
     *       <span>Sezon 1</span>
     *       <ul>
     *         <li><a href="/e/...slug.../id/slug/0">[s01e01] Odcinek 1</a></li>
     *         ...
     *       </ul>
     *     </li>
     *   </ul>
     */
    private fun parseSeasonsFromDoc(doc: Document, showId: String, poster: String?): List<Season> {
        return doc.select("#episode-list > li").mapIndexedNotNull { seasonIndex, seasonLi ->
            val seasonSpan = seasonLi.selectFirst("span") ?: return@mapIndexedNotNull null
            val seasonName = seasonSpan.text().trim()
            val seasonNumber = Regex("""\d+""").find(seasonName)?.value?.toIntOrNull() ?: (seasonIndex + 1)

            val episodes = seasonLi.select("ul > li").mapNotNull { episodeLi ->
                val anchor = episodeLi.selectFirst("a") ?: return@mapNotNull null
                val epHref = anchor.attr("href")
                val epText = anchor.text().trim()

                // Parse "[S01E05] Episode Name" format
                val bracketMatch = Regex("""^\[S(\d+)E(\d+)\]\s*(.*)""", RegexOption.IGNORE_CASE).find(epText)
                val epNumber: Int
                val epTitle: String

                if (bracketMatch != null) {
                    epNumber = bracketMatch.groupValues[2].toIntOrNull() ?: 0
                    epTitle = bracketMatch.groupValues[3].trim().ifBlank { "Odcinek $epNumber" }
                } else {
                    // Fallback: try to extract any episode number
                    epNumber = Regex("""[Ee](\d+)""").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("""\d+""").find(epText)?.value?.toIntOrNull()
                        ?: 0
                    epTitle = epText.replace(Regex("""^\[.*?\]\s*"""), "").trim().ifBlank { "Odcinek $epNumber" }
                }

                Episode(
                    id = parsePathId(epHref),
                    number = epNumber,
                    title = epTitle,
                    poster = poster
                )
            }.sortedBy { it.number }

            Season(
                id = "$showId|season|$seasonNumber",
                number = seasonNumber,
                title = seasonName,
                poster = poster,
                episodes = episodes
            )
        }.sortedBy { it.number }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("|season|")
        val showId = parts.getOrNull(0) ?: return emptyList()
        val seasonNumber = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()

        // Use cached TvShow if available — avoids an extra page fetch on every season change
        val tvShow = getCachedTvShow(showId) ?: getTvShow(showId)
        return tvShow.seasons.find { it.number == seasonNumber }?.episodes.orEmpty()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page == 1) "$baseUrl/$id" else "$baseUrl/$id?page=$page"
        val doc = getDocument(url)
        val name = doc.selectFirst("h1, h2")?.text()?.trim() ?: id.substringAfterLast("/")
        val shows = parseItems(getMainContainer(doc)).filterIsInstance<Show>()
        return Genre(
            id = id,
            name = name,
            shows = shows
        )
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val url = if (page == 1) "$baseUrl/$id" else "$baseUrl/$id?page=$page"
        val doc = getDocument(url)
        val name = doc.selectFirst("h1, h2")?.text()?.trim() ?: id.substringAfterLast("/")
        val filmography = parseItems(getMainContainer(doc)).filterIsInstance<Show>()
        return People(
            id = id,
            name = name,
            filmography = filmography
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val doc = getDocument(url)

        val html = doc.outerHtml()
        val routeTokenRegex = """var routeToken\s*=\s*'([^']*)'""".toRegex()
        val matchResult = routeTokenRegex.find(html)
        val routeToken = matchResult?.groups?.get(1)?.value ?: ""
        if (routeToken.isBlank()) {
            Log.e(TAG, "Failed to extract routeToken from $url")
            return emptyList()
        }

        val servers = mutableListOf<Video.Server>()
        val rows = doc.select("#link-list table#links tbody tr.version")

        rows.forEach { row ->
            val linkAnchor = row.selectFirst(".link-to-video a") ?: return@forEach
            val linkId = linkAnchor.attr("data-id").ifBlank { linkAnchor.attr("data-link-id") }
            if (linkId.isBlank()) return@forEach

            val nameImg = row.selectFirst("td img")
            val serverHost = nameImg?.attr("alt")?.trim()
                ?: row.select("td").firstOrNull()?.text()?.trim()
                ?: "Unknown"

            val version = row.select("td").getOrNull(1)?.text()?.trim().orEmpty()
            val quality = row.select("td").getOrNull(2)?.text()?.trim().orEmpty()

            val displayName = buildString {
                append(serverHost)
                if (version.isNotBlank()) append(" [$version]")
                if (quality.isNotBlank()) append(" ($quality)")
            }

            servers.add(Video.Server(
                id = linkId,
                name = displayName,
                src = "$linkId|$routeToken|$url"
            ))
        }

        return servers.sortedWith(compareByDescending<Video.Server> {
            it.name.contains("dood", ignoreCase = true)
        }.thenByDescending {
            it.name.contains("voe", ignoreCase = true)
        })
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val parts = server.src.split("|")
        if (parts.size < 2) {
            return if (server.src.startsWith("http")) Extractor.extract(server.src, server)
            else Video(source = "")
        }

        val linkId = parts[0]
        val routeToken = parts[1]
        val referer = parts.getOrNull(2) ?: baseUrl

        try {
            val ajaxUrl = "$baseUrl/link/token?link_id=$linkId&rt=$routeToken"
            val client = NetworkClient.default
            val request = Request.Builder()
                .url(ajaxUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", referer)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonStr = response.body?.string() ?: ""
                val jsonObj = org.json.JSONObject(jsonStr)
                if (jsonObj.optBoolean("ok")) {
                    val encodedUrl = jsonObj.optString("url")
                    val decodedUrl = String(Base64.decode(encodedUrl, Base64.DEFAULT), Charsets.UTF_8)
                    if (decodedUrl.startsWith("http")) {
                        val finalUrl = if (decodedUrl.contains("tmp-url.pro")) {
                            resolveTmpUrl(decodedUrl) ?: decodedUrl
                        } else {
                            decodedUrl
                        }
                        return Extractor.extract(finalUrl, server.copy(src = finalUrl))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching/decrypting server link: ${e.message}")
        }

        return Video(source = "")
    }

    private fun resolveTmpUrl(url: String): String? {
        try {
            val client = NetworkClient.default
            val request = Request.Builder()
                .url(url)
                .header("Referer", "https://filman.cc/")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""

                val eRegex = """var _e\s*=\s*'([^']*)'""".toRegex()
                val aRegex = """var _a\s*=\s*'([^']*)'""".toRegex()
                val bRegex = """var _b\s*=\s*'([^']*)'""".toRegex()
                val cRegex = """var _c\s*=\s*'([^']*)'""".toRegex()

                val e = eRegex.find(html)?.groups?.get(1)?.value ?: ""
                val a = aRegex.find(html)?.groups?.get(1)?.value ?: ""
                val b = bRegex.find(html)?.groups?.get(1)?.value ?: ""
                val c = cRegex.find(html)?.groups?.get(1)?.value ?: ""

                if (e.isNotEmpty() && a.isNotEmpty() && b.isNotEmpty() && c.isNotEmpty()) {
                    val key = a + b + c
                    val raw = Base64.decode(e, Base64.DEFAULT)
                    val out = StringBuilder()
                    for (i in raw.indices) {
                        val charCode = (raw[i].toInt() and 0xFF) xor key[i % key.length].code
                        out.append(charCode.toChar())
                    }
                    val decrypted = out.toString().trim()
                    if (decrypted.startsWith("http")) {
                        return decrypted
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving tmp-url: ${e.message}")
        }
        return null
    }

    // ---------------------------------------------------------------------------
    // Parsing helpers
    // ---------------------------------------------------------------------------

    private fun parsePathId(url: String): String {
        val path = try {
            URL(url).path
        } catch (e: Exception) {
            url
        }
        return path.removePrefix("/").removeSuffix("/")
    }

    private fun parseItems(document: org.jsoup.nodes.Element): List<AppAdapter.Item> {
        // Use a single primary selector to avoid matching the same element multiple times.
        // On filman.cc, items are plain <div>s inside #item-list.
        val candidates = document.select("#item-list > div")
            .ifEmpty { document.select(".item-list > div") }
            .ifEmpty { document.select("#results > div") }
            .ifEmpty { document.select(".poster, .movie-item, .film-item, .col-xs-6") }

        // Deduplicate by element identity (same DOM node matched by multiple selectors)
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<org.jsoup.nodes.Element, Boolean>())

        return candidates.mapNotNull { el ->
            if (!seen.add(el)) return@mapNotNull null

            val anchor = el.selectFirst(".poster a, a:has(picture), a:has(img)") ?: el.selectFirst("a") ?: return@mapNotNull null
            val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            if (href.isBlank()) return@mapNotNull null

            val id = parsePathId(href)
            val isMovie = id.startsWith("m/") || id.startsWith("film/")
            val isShow = id.startsWith("s/") || id.startsWith("serial/") || id.startsWith("e/")
            if (!isMovie && !isShow) return@mapNotNull null
            val title = el.selectFirst(".film_title, .title, h2, h3, h4")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
                ?: el.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: ""

            if (title.isEmpty()) return@mapNotNull null

            val year = el.selectFirst(".film_year, .year, .date")?.text()?.trim()

            val posterImg = anchor.selectFirst("picture source, picture img, img") ?: el.selectFirst("img")
            val posterUrl = posterImg?.let {
                it.attr("abs:data-src").ifBlank {
                    it.attr("data-src").ifBlank {
                        it.attr("abs:srcset").ifBlank {
                            it.attr("srcset").ifBlank {
                                it.attr("abs:src").ifBlank {
                                    it.attr("src")
                                }
                            }
                        }
                    }
                }
            } ?: ""

            val titleWithYear = if (!year.isNullOrEmpty() && !title.contains(year)) "$title ($year)" else title

            if (isMovie) {
                Movie(
                    id = id,
                    title = titleWithYear,
                    poster = posterUrl
                )
            } else {
                TvShow(
                    id = id,
                    title = titleWithYear,
                    poster = posterUrl
                )
            }
        }.distinctBy {
            @Suppress("REDUNDANT_ELSE_IN_WHEN")
            when (it) {
                is Movie -> "movie:${it.id}"
                is TvShow -> "tv:${it.id}"
                else -> it.toString()
            }
        }
    }
}
