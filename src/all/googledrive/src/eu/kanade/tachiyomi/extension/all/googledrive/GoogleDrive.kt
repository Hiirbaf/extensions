package eu.kanade.tachiyomi.extension.all.googledrive

import android.app.Application
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ProtocolException
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.commonEmptyRequestBody
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import java.security.MessageDigest

class GoogleDrive :
    HttpSource(),
    ConfigurableSource {

    override val name = "Google Drive"

    override val id = 4222017068256633289

    override var baseUrl = "https://drive.google.com"

    // Hack to manipulate what gets opened in webview
    private val baseUrlInternal by lazy {
        preferences.domainList.split(";").firstOrNull()
    }

    override val lang = "all"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Overriding headersBuilder() seems to cause issues with webview
    private val getHeaders = headers.newBuilder().apply {
        add("Accept", "*/*")
        add("Connection", "keep-alive")
        add("Cookie", getCookie("https://drive.google.com"))
        add("Host", "drive.google.com")
    }.build()

    private var nextPageToken: String? = ""

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = parsePage(popularMangaRequest(page), page)

    override fun popularMangaRequest(page: Int): Request {
        require(!baseUrlInternal.isNullOrEmpty()) { "Enter drive path(s) in extension settings." }

        val match = DRIVE_FOLDER_REGEX.matchEntire(baseUrlInternal!!)!!
        val folderId = match.groups["id"]!!.value
        val recurDepth = match.groups["depth"]?.value ?: ""
        baseUrl = "https://drive.google.com/drive/folders/$folderId"

        return GET(
            "https://drive.google.com/drive/folders/$folderId$recurDepth",
            headers = getHeaders,
        )
    }

    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val urlFilter = filterList.find { it is URLFilter } as URLFilter

        return if (urlFilter.state.isEmpty()) {
            val req = searchMangaRequest(page, query, filters)

            if (query.isEmpty()) {
                parsePage(req, page)
            } else {
                val parentId = req.url.pathSegments.last()
                val cleanQuery = URLEncoder.encode(query, "UTF-8")
                val genMultiFormReq = searchReq(parentId, cleanQuery)

                parsePage(req, page, genMultiFormReq)
            }
        } else {
            addSinglePage(urlFilter.state)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        require(!baseUrlInternal.isNullOrEmpty()) { "Enter drive path(s) in extension settings." }

        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val serverFilter = filterList.find { it is ServerFilter } as ServerFilter
        val serverUrl = serverFilter.toUriPart()

        val match = DRIVE_FOLDER_REGEX.matchEntire(serverUrl)!!
        val folderId = match.groups["id"]!!.value
        val recurDepth = match.groups["depth"]?.value ?: ""
        baseUrl = "https://drive.google.com/drive/folders/$folderId"

        return GET(
            "https://drive.google.com/drive/folders/$folderId$recurDepth",
            headers = getHeaders,
        )
    }

    // ============================== FILTERS ===============================

    override fun getFilterList(): FilterList = FilterList(
        ServerFilter(getDomains()),
        Filter.Separator(),
        Filter.Header("Add single folder"),
        URLFilter(),
    )

    private class ServerFilter(domains: Array<Pair<String, String>>) :
        UriPartFilter(
            "Select drive path",
            domains,
        )

    private fun getDomains(): Array<Pair<String, String>> {
        if (preferences.domainList.isBlank()) return emptyArray()
        return preferences.domainList.split(";").map {
            val name = DRIVE_FOLDER_REGEX.matchEntire(it)!!.groups["name"]?.let {
                it.value.substringAfter("[").substringBeforeLast("]")
            }
            Pair(name ?: it.toHttpUrl().encodedPath, it)
        }.toTypedArray()
    }

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class URLFilter : Filter.Text("Url")

    // =========================== Manga Details ============================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val parsed = json.decodeFromString<LinkData>(manga.url)
        return GET(parsed.url, headers = getHeaders)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val parsed = json.decodeFromString<LinkData>(manga.url)

        if (parsed.type == "single") return manga

        val folderId = DRIVE_FOLDER_REGEX.matchEntire(parsed.url)!!.groups["id"]!!.value

        val driveDocument = try {
            client.newCall(GET(parsed.url, headers = getHeaders)).execute().asJsoup()
        } catch (a: ProtocolException) {
            null
        } ?: return manga

        // Get cover
        val coverResponse = client.newCall(
            createPost(driveDocument, folderId, nextPageToken, searchReqWithType(folderId, "cover", IMAGE_MIMETYPE)),
        ).execute().parseAs<PostResponse> { JSON_REGEX.find(it)!!.groupValues[1] }

        coverResponse.items?.firstOrNull()?.let {
            manga.thumbnail_url = "https://drive.google.com/uc?id=${it.id}"
        }

        // Get details
        val detailsResponse = client.newCall(
            createPost(driveDocument, folderId, nextPageToken, searchReqWithType(folderId, "details.json", "")),
        ).execute().parseAs<PostResponse> { JSON_REGEX.find(it)!!.groupValues[1] }

        detailsResponse.items?.firstOrNull()?.let {
            val newPostHeaders = getHeaders.newBuilder().apply {
                add("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                set("Host", "drive.usercontent.google.com")
                add("Origin", "https://drive.google.com")
                add("Referer", "https://drive.google.com/")
                add("X-Drive-First-Party", "DriveWebUi")
                add("X-Json-Requested", "true")
            }.build()

            val newPostUrl = "https://drive.usercontent.google.com/uc?id=${it.id}&authuser=0&export=download"

            val newResponse = client.newCall(
                POST(newPostUrl, headers = newPostHeaders, body = commonEmptyRequestBody),
            ).execute().parseAs<DownloadResponse> { JSON_REGEX.find(it)!!.groupValues[1] }

            val downloadHeaders = headers.newBuilder().apply {
                add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                add("Connection", "keep-alive")
                add("Cookie", getCookie("https://drive.usercontent.google.com"))
                add("Host", "drive.usercontent.google.com")
            }.build()

            client.newCall(
                GET(newResponse.downloadUrl, headers = downloadHeaders),
            ).execute().parseAs<DetailsJson>().let { t ->
                t.title?.let { manga.title = it }
                t.author?.let { manga.author = it }
                t.artist?.let { manga.artist = it }
                t.description?.let { manga.description = it }
                t.genre?.let { manga.genre = it.joinToString(", ") }
                t.status?.let { manga.status = it.toIntOrNull() ?: SManga.UNKNOWN }
            }
        }

        return manga
    }

    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()

    // ============================== Chapters ==============================

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val chapterList = mutableListOf<SChapter>()
        val parsed = json.decodeFromString<LinkData>(manga.url)

        if (parsed.type == "single") {
            return listOf(
                SChapter.create().apply {
                    name = "Chapter"
                    scanlator = parsed.info!!.size
                    url = parsed.url
                    chapter_number = 1F
                    date_upload = -1L
                },
            )
        }

        val match = DRIVE_FOLDER_REGEX.matchEntire(parsed.url)!!
        val maxRecursionDepth = match.groups["depth"]?.let {
            it.value.substringAfter("#").substringBefore(",").toInt()
        } ?: 2
        val (start, stop) = match.groups["range"]?.let {
            it.value.substringAfter(",").split(",").map { it.toInt() }
        } ?: listOf(null, null)

        fun traverseFolder(folderUrl: String, path: String, recursionDepth: Int = 0) {
            if (recursionDepth == maxRecursionDepth) return

            val folderId = DRIVE_FOLDER_REGEX.matchEntire(folderUrl)!!.groups["id"]!!.value

            val driveDocument = try {
                client.newCall(GET(folderUrl, headers = getHeaders)).execute().asJsoup()
            } catch (a: ProtocolException) {
                throw Exception("Unable to get items, check webview")
            }

            if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) return

            var pageToken: String? = ""
            var counter = 1

            while (pageToken != null) {
                val response = client.newCall(
                    createPost(driveDocument, folderId, pageToken),
                ).execute()

                val parsedPage = response.parseAs<PostResponse> {
                    JSON_REGEX.find(it)!!.groupValues[1]
                }

                if (parsedPage.items == null) throw Exception("Failed to load items, please log in through webview")
                parsedPage.items.forEachIndexed { index, it ->
                    // A folder = a chapter
                    if (it.mimeType.endsWith(".folder")) {
                        val pathName = if (preferences.trimChapterInfo) path.trimInfo() else path

                        if (start != null && maxRecursionDepth == 1 && counter < start) {
                            counter++
                            return@forEachIndexed
                        }
                        if (stop != null && maxRecursionDepth == 1 && counter > stop) return

                        chapterList.add(
                            SChapter.create().apply {
                                name = if (preferences.trimChapterName) it.title.trimInfo() else it.title
                                url = LinkData(
                                    "https://drive.google.com/drive/folders/${it.id}",
                                    "multi",
                                ).toJsonString()
                                chapter_number =
                                    ITEM_NUMBER_REGEX.find(it.title.trimInfo())?.groupValues?.get(1)
                                        ?.toFloatOrNull() ?: (index + 1).toFloat()
                                date_upload = -1L
                                scanlator = if (preferences.scanlatorOrder) {
                                    "/$pathName"
                                } else {
                                    "/$pathName"
                                }
                            },
                        )
                        counter++

                        // Recurse into sub-folders if needed
                        if (recursionDepth + 1 < maxRecursionDepth) {
                            traverseFolder(
                                "https://drive.google.com/drive/folders/${it.id}",
                                if (path.isEmpty()) it.title else "$path/${it.title}",
                                recursionDepth + 1,
                            )
                        }
                    }
                }

                pageToken = parsedPage.nextPageToken
            }
        }

        traverseFolder(parsed.url, "")

        return chapterList.reversed()
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    // ============================== Pages ==============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val parsed = json.decodeFromString<LinkData>(chapter.url)

        // Single image file
        if (parsed.type == "single") {
            return listOf(Page(0, "", parsed.url))
        }

        // Folder of images
        val pages = mutableListOf<Page>()
        val folderId = DRIVE_FOLDER_REGEX.matchEntire(parsed.url)!!.groups["id"]!!.value

        val driveDocument = try {
            client.newCall(GET(parsed.url, headers = getHeaders)).execute().asJsoup()
        } catch (a: ProtocolException) {
            throw Exception("Unable to get chapter pages, check webview")
        }

        var pageToken: String? = ""

        while (pageToken != null) {
            val response = client.newCall(
                createPost(driveDocument, folderId, pageToken),
            ).execute()

            val parsedPage = response.parseAs<PostResponse> {
                JSON_REGEX.find(it)!!.groupValues[1]
            }

            if (parsedPage.items == null) throw Exception("Failed to load pages, please log in through webview")

            parsedPage.items.forEach { item ->
                if (item.mimeType.startsWith("image")) {
                    pages.add(
                        Page(pages.size, "", "https://drive.google.com/uc?id=${item.id}"),
                    )
                }
            }

            pageToken = parsedPage.nextPageToken
        }

        return pages
    }

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    override suspend fun getImageUrl(page: Page) = page.imageUrl!!

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun addSinglePage(folderUrl: String): MangasPage {
        val match =
            DRIVE_FOLDER_REGEX.matchEntire(folderUrl) ?: throw Exception("Invalid drive url")
        val recurDepth = match.groups["depth"]?.value ?: ""

        val manga = SManga.create().apply {
            title = match.groups["name"]?.value?.substringAfter("[")?.substringBeforeLast("]")
                ?: "Folder"
            url = LinkData(
                "https://drive.google.com/drive/folders/${match.groups["id"]!!.value}$recurDepth",
                "multi",
            ).toJsonString()
            thumbnail_url = ""
        }
        return MangasPage(listOf(manga), false)
    }

    private fun createPost(
        document: Document,
        folderId: String,
        pageToken: String?,
        getMultiFormPath: (String, String, String) -> String = { folderIdStr, nextPageTokenStr, keyStr ->
            defaultGetRequest(folderIdStr, nextPageTokenStr, keyStr)
        },
    ): Request {
        val keyScript = document.select("script").first { script ->
            KEY_REGEX.find(script.data()) != null
        }.data()
        val key = KEY_REGEX.find(keyScript)?.groupValues?.get(1) ?: ""

        val versionScript = document.select("script").first { script ->
            KEY_REGEX.find(script.data()) != null
        }.data()
        val driveVersion = VERSION_REGEX.find(versionScript)?.groupValues?.get(1) ?: ""
        val sapisid =
            client.cookieJar.loadForRequest("https://drive.google.com".toHttpUrl()).firstOrNull {
                it.name == "SAPISID" || it.name == "__Secure-3PAPISID"
            }?.value ?: ""

        val requestUrl = getMultiFormPath(folderId, pageToken ?: "", key)
        val body = """--$BOUNDARY
                    |content-type: application/http
                    |content-transfer-encoding: binary
                    |
                    |GET $requestUrl
                    |X-Goog-Drive-Client-Version: $driveVersion
                    |authorization: ${generateSapisidhashHeader(sapisid)}
                    |x-goog-authuser: 0
                    |
                    |--$BOUNDARY--""".trimMargin("|")
            .toRequestBody("multipart/mixed; boundary=\"$BOUNDARY\"".toMediaType())

        val postUrl = buildString {
            append("https://clients6.google.com/batch/drive/v2internal")
            append("?${'$'}ct=multipart/mixed; boundary=\"$BOUNDARY\"")
            append("&key=$key")
        }

        val postHeaders = headers.newBuilder().apply {
            add("Content-Type", "text/plain; charset=UTF-8")
            add("Origin", "https://drive.google.com")
            add("Cookie", getCookie("https://drive.google.com"))
        }.build()

        return POST(postUrl, body = body, headers = postHeaders)
    }

    private fun parsePage(
        request: Request,
        page: Int,
        genMultiFormReq: ((String, String, String) -> String)? = null,
    ): MangasPage {
        val mangaList = mutableListOf<SManga>()

        val recurDepth = request.url.encodedFragment?.let { "#$it" } ?: ""

        val folderId = DRIVE_FOLDER_REGEX.matchEntire(request.url.toString())!!.groups["id"]!!.value

        val driveDocument = try {
            client.newCall(request).execute().asJsoup()
        } catch (a: ProtocolException) {
            throw Exception("Unable to get items, check webview")
        }

        if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) {
            return MangasPage(emptyList(), false)
        }

        if (page == 1) nextPageToken = ""
        val post = if (genMultiFormReq == null) {
            createPost(driveDocument, folderId, nextPageToken)
        } else {
            createPost(
                driveDocument,
                folderId,
                nextPageToken,
                genMultiFormReq,
            )
        }
        val response = client.newCall(post).execute()

        val parsed = response.parseAs<PostResponse> {
            JSON_REGEX.find(it)!!.groupValues[1]
        }

        if (parsed.items == null) throw Exception("Failed to load items, please log in through webview")
        parsed.items.forEach { it ->
            // Top-level folders = manga entries
            if (it.mimeType.endsWith(".folder")) {
                mangaList.add(
                    SManga.create().apply {
                        title = if (preferences.trimMangaInfo) it.title.trimInfo() else it.title
                        url = LinkData(
                            "https://drive.google.com/drive/folders/${it.id}$recurDepth",
                            "multi",
                        ).toJsonString()
                        thumbnail_url = ""
                    },
                )
            }
            // A lone image file at root level = single-page manga
            if (it.mimeType.startsWith("image")) {
                mangaList.add(
                    SManga.create().apply {
                        title = if (preferences.trimMangaInfo) it.title.trimInfo() else it.title
                        url = LinkData(
                            "https://drive.google.com/uc?id=${it.id}",
                            "single",
                            LinkDataInfo(
                                it.title,
                                it.fileSize?.toLongOrNull()?.let { formatBytes(it) } ?: "",
                            ),
                        ).toJsonString()
                        thumbnail_url = ""
                    },
                )
            }
        }

        nextPageToken = parsed.nextPageToken

        return MangasPage(mangaList, nextPageToken != null)
    }

    // https://github.com/yt-dlp/yt-dlp/blob/8f0be90ecb3b8d862397177bb226f17b245ef933/yt_dlp/extractor/youtube.py#L573
    private fun generateSapisidhashHeader(
        SAPISID: String,
        origin: String = "https://drive.google.com",
    ): String {
        val timeNow = System.currentTimeMillis() / 1000
        val sapisidhash = MessageDigest
            .getInstance("SHA-1")
            .digest("$timeNow $SAPISID $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timeNow}_$sapisidhash"
    }

    private fun String.trimInfo(): String {
        var newString = this.replaceFirst("""^\[\w+\] ?""".toRegex(), "")
        val regex = """( ?\[[\s\w-]+\]| ?\([\s\w-]+\))(\.cbz|\.cbr|\.zip)?${'$'}""".toRegex()

        while (regex.containsMatchIn(newString)) {
            newString = regex.replace(newString) { matchResult ->
                matchResult.groups[2]?.value ?: ""
            }
        }

        return newString.trim()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
        bytes > 1 -> "$bytes bytes"
        bytes == 1L -> "$bytes byte"
        else -> ""
    }

    private fun getCookie(url: String): String {
        val cookieList = client.cookieJar.loadForRequest(url.toHttpUrl())
        return if (cookieList.isNotEmpty()) {
            cookieList.joinToString("; ") { "${it.name}=${it.value}" }
        } else {
            ""
        }
    }

    private fun LinkData.toJsonString(): String = json.encodeToString(this)

    private fun isFolder(text: String) = DRIVE_FOLDER_REGEX matches text

    /*
     * Stolen from the MangaDex manga extension
     *
     * This will likely need to be removed or revisited when the app migrates the
     * extension preferences screen to Compose.
     */
    private fun setupEditTextFolderValidator(editText: EditText) {
        editText.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                    // Do nothing.
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // Do nothing.
                }

                override fun afterTextChanged(editable: Editable?) {
                    requireNotNull(editable)

                    val text = editable.toString()

                    val isValid = text.isBlank() || text
                        .split(";")
                        .map(String::trim)
                        .all(::isFolder)

                    editText.error = if (!isValid) {
                        "${
                            text.split(";").first { !isFolder(it) }
                        } is not a valid google drive folder"
                    } else {
                        null
                    }
                    editText.rootView.findViewById<Button>(android.R.id.button1)
                        ?.isEnabled = editText.error == null
                }
            },
        )
    }

    companion object {
        private const val DOMAIN_PREF_KEY = "domain_list"
        private const val DOMAIN_PREF_DEFAULT = ""

        private const val TRIM_MANGA_KEY = "trim_manga_info"
        private const val TRIM_MANGA_DEFAULT = false

        private const val TRIM_CHAPTER_NAME_KEY = "trim_chapter_name"
        private const val TRIM_CHAPTER_NAME_DEFAULT = true

        private const val TRIM_CHAPTER_INFO_KEY = "trim_chapter_info"
        private const val TRIM_CHAPTER_INFO_DEFAULT = false

        private const val SCANLATOR_ORDER_KEY = "scanlator_order"
        private const val SCANLATOR_ORDER_DEFAULT = false

        private val DRIVE_FOLDER_REGEX = Regex(
            """(?<n>\[[^\[\];]+\])?https?:\/\/(?:docs|drive)\.google\.com\/drive(?:\/[^\/]+)*?\/folders\/(?<id>[\w-]{28,})(?:\?[^;#]+)?(?<depth>#\d+(?<range>,\d+,\d+)?)?${'$'}""",
        )
        private val KEY_REGEX = Regex(""""(\w{39})"""")
        private val VERSION_REGEX = Regex(""""([^"]+web-frontend[^"]+)"""")
        private val JSON_REGEX = Regex("""(?:)\s*(\{(.+)\})\s*(?:)""", RegexOption.DOT_MATCHES_ALL)
        private const val BOUNDARY = "=====vc17a3rwnndj====="

        private val ITEM_NUMBER_REGEX = Regex(""" - (?:Vol\.\d+ )?(?:Ch\.)?(\d+(?:\.\d+)?)\b""")
    }

    private val SharedPreferences.domainList
        get() = getString(DOMAIN_PREF_KEY, DOMAIN_PREF_DEFAULT)!!

    private val SharedPreferences.trimMangaInfo
        get() = getBoolean(TRIM_MANGA_KEY, TRIM_MANGA_DEFAULT)

    private val SharedPreferences.trimChapterName
        get() = getBoolean(TRIM_CHAPTER_NAME_KEY, TRIM_CHAPTER_NAME_DEFAULT)

    private val SharedPreferences.trimChapterInfo
        get() = getBoolean(TRIM_CHAPTER_INFO_KEY, TRIM_CHAPTER_INFO_DEFAULT)

    private val SharedPreferences.scanlatorOrder
        get() = getBoolean(SCANLATOR_ORDER_KEY, SCANLATOR_ORDER_DEFAULT)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = DOMAIN_PREF_KEY
            title = "Enter drive paths to be shown in extension"
            summary = """Enter links of drive folders to be shown in extension
                |Enter as a semicolon `;` separated list
            """.trimMargin()
            this.setDefaultValue(DOMAIN_PREF_DEFAULT)
            dialogTitle = "Path list"
            dialogMessage = """Separate paths with a semicolon.
                |- (optional) Add [] before url to customize name. For example: [My Manga]https://drive.google.com/drive/folders/whatever
                |- (optional) add #<integer> to limit the depth of recursion when loading chapters, default is 2. For example: https://drive.google.com/drive/folders/whatever#5
                |- (optional) add #depth,start,stop (all integers) to specify range when loading chapters. Only works if depth is 1. For example: https://drive.google.com/drive/folders/whatever#1,2,6
            """.trimMargin()

            setOnBindEditTextListener(::setupEditTextFolderValidator)

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res =
                        preferences.edit().putString(DOMAIN_PREF_KEY, newValue as String).commit()
                    Toast.makeText(
                        screen.context,
                        "Restart Mihon to apply changes",
                        Toast.LENGTH_LONG,
                    ).show()
                    res
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = TRIM_MANGA_KEY
            title = "Trim info from manga titles"
            setDefaultValue(TRIM_MANGA_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = TRIM_CHAPTER_NAME_KEY
            title = "Trim info from chapter name"
            setDefaultValue(TRIM_CHAPTER_NAME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = TRIM_CHAPTER_INFO_KEY
            title = "Trim info from chapter info"
            setDefaultValue(TRIM_CHAPTER_INFO_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = SCANLATOR_ORDER_KEY
            title = "Switch order of file path and size"
            setDefaultValue(SCANLATOR_ORDER_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }
}
