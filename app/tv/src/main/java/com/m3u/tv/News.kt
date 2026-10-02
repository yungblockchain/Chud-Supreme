package com.m3u.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.net.HttpURLConnection
import java.net.URI
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * News desks. Each category is a list of RSS or Atom URLs, saved on the device.
 * Starters are the publishers' own feeds. RSSHub and RSS-Bridge both emit ordinary
 * feeds, so a link from either can be added. Miniflux is a reader you host yourself;
 * it has no public keyless API, so the same feed URL is pasted here.
 */

private const val NEWS_PREFS = "chud_news"
private const val NEWS_KEY = "categories"

@Immutable
private data class NewsFeed(val id: String, val name: String, val url: String)

@Immutable
private data class NewsCategory(val id: String, val name: String, val feeds: List<NewsFeed>)

@Immutable
private data class NewsStory(
    val title: String,
    val link: String,
    val summary: String,
    val source: String,
    val whenLabel: String,
    val time: Long,
)

private data class CatalogSource(val group: String, val name: String, val url: String)

private val CATALOG = listOf(
    CatalogSource("Football", "BBC Sport Football", "https://feeds.bbci.co.uk/sport/football/rss.xml"),
    CatalogSource("Football", "Guardian Football", "https://www.theguardian.com/football/rss"),
    CatalogSource("Football", "Guardian Premier League", "https://www.theguardian.com/football/premierleague/rss"),
    CatalogSource("Football", "Sky Sports Football", "https://www.skysports.com/rss/12040"),
    CatalogSource("Sport", "BBC Sport", "https://feeds.bbci.co.uk/sport/rss.xml"),
    CatalogSource("Sport", "Guardian Sport", "https://www.theguardian.com/uk/sport/rss"),
    CatalogSource("Sport", "BBC Formula 1", "https://feeds.bbci.co.uk/sport/formula1/rss.xml"),
    CatalogSource("World", "BBC News", "https://feeds.bbci.co.uk/news/rss.xml"),
    CatalogSource("World", "BBC UK", "https://feeds.bbci.co.uk/news/uk/rss.xml"),
    CatalogSource("World", "BBC Business", "https://feeds.bbci.co.uk/news/business/rss.xml"),
    CatalogSource("World", "Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml"),
    CatalogSource("World", "Guardian World", "https://www.theguardian.com/world/rss"),
    CatalogSource("World", "Guardian UK", "https://www.theguardian.com/uk-news/rss"),
    CatalogSource("World", "NPR News", "https://feeds.npr.org/1001/rss.xml"),
    CatalogSource("Tech", "The Verge", "https://www.theverge.com/rss/index.xml"),
    CatalogSource("Tech", "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index"),
    CatalogSource("Tech", "Guardian Technology", "https://www.theguardian.com/uk/technology/rss"),
    CatalogSource("Tech", "Hacker News", "https://hnrss.org/frontpage"),
    CatalogSource("Local", "BBC London", "https://feeds.bbci.co.uk/news/england/london/rss.xml"),
    CatalogSource("Local", "BBC Manchester", "https://feeds.bbci.co.uk/news/england/manchester/rss.xml"),
    CatalogSource("Local", "BBC Birmingham", "https://feeds.bbci.co.uk/news/england/birmingham_and_black_country/rss.xml"),
    CatalogSource("Local", "BBC Leeds", "https://feeds.bbci.co.uk/news/england/leeds_and_west_yorkshire/rss.xml"),
    CatalogSource("Local", "BBC England", "https://feeds.bbci.co.uk/news/england/rss.xml"),
    CatalogSource("Local", "BBC Scotland", "https://feeds.bbci.co.uk/news/scotland/rss.xml"),
    CatalogSource("Local", "BBC Wales", "https://feeds.bbci.co.uk/news/wales/rss.xml"),
    CatalogSource("Local", "BBC Northern Ireland", "https://feeds.bbci.co.uk/news/northern_ireland/rss.xml"),
)

@Composable
fun NewsScreen() {
    val context = LocalContext.current
    val prefs = remember {
        context.applicationContext.getSharedPreferences(NEWS_PREFS, Context.MODE_PRIVATE)
    }
    var categories by remember { mutableStateOf(loadCategories(prefs)) }
    var activeId by remember { mutableStateOf(categories.firstOrNull()?.id.orEmpty()) }
    var stories by remember { mutableStateOf<List<NewsStory>>(emptyList()) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<NewsStory?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var openNote by remember { mutableStateOf<String?>(null) }

    fun save(next: List<NewsCategory>) {
        categories = next
        prefs.edit().putString(NEWS_KEY, encodeCategories(next)).apply()
        if (next.none { it.id == activeId }) activeId = next.firstOrNull()?.id.orEmpty()
    }

    val active = categories.firstOrNull { it.id == activeId } ?: categories.firstOrNull()
    val feedKey = active?.feeds?.joinToString("|") { it.url }.orEmpty()

    LaunchedEffect(active?.id, feedKey, reload) {
        val category = active
        if (category == null || category.feeds.isEmpty()) {
            stories = emptyList()
            errors = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val loaded = NewsClient.load(category.feeds)
        stories = loaded.stories
        errors = loaded.errors
        loading = false
    }

    val story = selected
    if (story != null) {
        StoryPage(
            story = story,
            note = openNote,
            onBack = {
                selected = null
                openNote = null
            },
            onOpen = {
                openNote = openStory(context, story.link)
            },
        )
        return
    }

    if (managing && active != null) {
        SourcesPage(
            category = active,
            onBack = { managing = false },
            onChange = { next -> save(categories.map { if (it.id == next.id) next else it }) },
            onDelete = {
                save(categories.filter { it.id != active.id })
                managing = false
            },
            onRestore = {
                val starters = starterCategories()
                save(starters)
                activeId = starters.first().id
                managing = false
            },
            onCreate = { name ->
                val created = NewsCategory(id = slug(name), name = name.take(40), feeds = emptyList())
                save(categories + created)
                activeId = created.id
            },
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 36.dp, end = 48.dp, top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "News",
                    color = TvColors.TextPrimary,
                    fontFamily = TvFonts.Accent,
                    fontSize = 28.sp,
                    modifier = Modifier.weight(1f),
                )
                TvActionButton(text = "Refresh", icon = Icons.Rounded.Refresh, onClick = { reload++ })
                TvActionButton(
                    text = "Sources",
                    icon = Icons.Rounded.RssFeed,
                    onClick = { if (active != null) managing = true },
                    enabled = active != null,
                )
            }
        }
        item {
            Text(
                text = "Categories are yours. Starters are the publishers' own RSS. Paste a feed from RSSHub or RSS-Bridge. Miniflux uses those same links.",
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 15.sp,
                modifier = Modifier.widthIn(max = 920.dp),
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(categories, key = { it.id }) { category ->
                    TvActionButton(
                        text = category.name,
                        icon = Icons.Rounded.Newspaper,
                        selected = category.id == active?.id,
                        onClick = { activeId = category.id },
                    )
                }
                item {
                    TvActionButton(
                        text = "New category",
                        icon = Icons.Rounded.Add,
                        onClick = { managing = true },
                    )
                }
            }
        }
        item {
            Text(
                text = when {
                    active == null -> "No categories yet. Open Sources and make one."
                    active.feeds.isEmpty() -> "No sources in ${active.name} yet."
                    else -> active.feeds.joinToString("   ·   ") { it.name }
                },
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 15.sp,
            )
        }
        when {
            active == null -> item {
                TvActionButton(
                    text = "Restore starter categories",
                    icon = Icons.Rounded.Refresh,
                    onClick = {
                        val starters = starterCategories()
                        save(starters)
                        activeId = starters.first().id
                    },
                )
            }
            loading && stories.isEmpty() -> item { NewsStatus("Loading stories…") }
            stories.isEmpty() && errors.isNotEmpty() -> item { NewsStatus(errors.joinToString("  ")) }
            stories.isEmpty() -> item { NewsStatus("Add a source and the stories land here.") }
            else -> {
                if (errors.isNotEmpty()) item { NewsStatus(errors.joinToString("  ")) }
                items(stories, key = { "${it.source}:${it.link.ifBlank { it.title }}" }) { itemStory ->
                    FocusFrame(
                        onClick = { selected = itemStory },
                        semanticsLabel = itemStory.title,
                        modifier = Modifier.widthIn(max = 980.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            Text(
                                text = listOf(itemStory.source, itemStory.whenLabel).filter { it.isNotBlank() }.joinToString("   ·   "),
                                color = TvColors.Focus,
                                fontFamily = TvFonts.Body,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = itemStory.title,
                                color = TvColors.TextPrimary,
                                fontFamily = TvFonts.Body,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryPage(
    story: NewsStory,
    note: String?,
    onBack: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 36.dp, end = 48.dp, top = 28.dp, bottom = 48.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvActionButton(text = "Back", icon = Icons.Rounded.Article, onClick = onBack)
            if (story.link.isNotBlank()) {
                TvActionButton(text = "Open original", icon = Icons.Rounded.OpenInNew, onClick = onOpen)
            }
        }
        Text(
            text = listOf(story.source, story.whenLabel).filter { it.isNotBlank() }.joinToString("   ·   "),
            color = TvColors.Focus,
            fontFamily = TvFonts.Body,
            fontSize = 15.sp,
        )
        Text(
            text = story.title,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Accent,
            fontSize = 28.sp,
            modifier = Modifier.widthIn(max = 920.dp),
        )
        Text(
            text = story.summary.ifBlank { "No summary in this feed." },
            color = TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontSize = 18.sp,
            modifier = Modifier.widthIn(max = 920.dp),
        )
        note?.let { NewsStatus(it) }
    }
}

@Composable
private fun SourcesPage(
    category: NewsCategory,
    onBack: () -> Unit,
    onChange: (NewsCategory) -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var rename by remember(category.id) { mutableStateOf(category.name) }
    var feedName by remember { mutableStateOf("") }
    var feedUrl by remember { mutableStateOf("") }
    var hubBase by remember { mutableStateOf("https://rsshub.app") }
    var hubRoute by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var confirmDelete by remember(category.id) { mutableStateOf(false) }

    fun add(name: String, url: String) {
        val trimmed = url.trim()
        if (!allowedFeedUrl(trimmed)) {
            note = "The source needs a full http or https URL."
            return
        }
        if (category.feeds.any { it.url == trimmed }) {
            note = "That source is already in this category."
            return
        }
        val label = name.trim().ifBlank { hostOf(trimmed) }.take(60)
        onChange(category.copy(feeds = category.feeds + NewsFeed(slug(label), label, trimmed)))
        feedName = ""
        feedUrl = ""
        hubRoute = ""
        note = "Added $label."
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 36.dp, end = 48.dp, top = 28.dp, bottom = 48.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Sources · ${category.name}",
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Accent,
                fontSize = 28.sp,
                modifier = Modifier.weight(1f),
            )
            TvActionButton(text = "Done", icon = Icons.Rounded.Newspaper, onClick = onBack)
        }
        DialTextField(
            label = "Category name",
            value = rename,
            onValueChange = { rename = it },
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
            readOnly = false,
            onDone = {
                val trimmed = rename.trim()
                if (trimmed.isNotEmpty()) onChange(category.copy(name = trimmed.take(40)))
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvActionButton(
                text = "Rename",
                icon = Icons.Rounded.Article,
                onClick = {
                    val trimmed = rename.trim()
                    if (trimmed.isNotEmpty()) onChange(category.copy(name = trimmed.take(40)))
                },
            )
            TvActionButton(
                text = if (confirmDelete) "Confirm delete" else "Delete category",
                icon = Icons.Rounded.Delete,
                onClick = {
                    if (!confirmDelete) confirmDelete = true else onDelete()
                },
            )
        }
        if (category.feeds.isEmpty()) {
            NewsStatus("Nothing saved in ${category.name} yet.")
        }
        category.feeds.forEach { feed ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = feed.name, color = TvColors.TextPrimary, fontFamily = TvFonts.Body, fontSize = 16.sp)
                    Text(
                        text = feed.url,
                        color = TvColors.TextMuted,
                        fontFamily = TvFonts.Body,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TvActionButton(
                    text = "Remove",
                    icon = Icons.Rounded.Delete,
                    onClick = { onChange(category.copy(feeds = category.feeds.filter { it.id != feed.id })) },
                )
            }
        }
        DialTextField(
            label = "Source name",
            value = feedName,
            onValueChange = { feedName = it },
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next,
            readOnly = false,
            placeholder = "My local paper",
        )
        DialTextField(
            label = "Feed URL",
            value = feedUrl,
            onValueChange = { feedUrl = it },
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            readOnly = false,
            placeholder = "https://example.com/rss.xml",
            onDone = { add(feedName, feedUrl) },
        )
        TvActionButton(text = "Add source", icon = Icons.Rounded.Add, onClick = { add(feedName, feedUrl) })
        DialTextField(
            label = "RSSHub instance",
            value = hubBase,
            onValueChange = { hubBase = it },
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            readOnly = false,
            placeholder = "https://rsshub.app",
        )
        DialTextField(
            label = "RSSHub route",
            value = hubRoute,
            onValueChange = { hubRoute = it },
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            readOnly = false,
            placeholder = "/bbc/world",
            onDone = {
                val route = hubRoute.trim()
                if (route.isEmpty()) {
                    note = "Add an RSSHub route, such as /bbc/world."
                } else {
                    val path = if (route.startsWith("/")) route else "/$route"
                    add(feedName.ifBlank { "RSSHub" }, hubBase.trim().trimEnd('/') + path)
                }
            },
        )
        TvActionButton(
            text = "Add RSSHub",
            icon = Icons.Rounded.RssFeed,
            onClick = {
                val route = hubRoute.trim()
                if (route.isEmpty()) {
                    note = "Add an RSSHub route, such as /bbc/world."
                } else {
                    val path = if (route.startsWith("/")) route else "/$route"
                    add(feedName.ifBlank { "RSSHub" }, hubBase.trim().trimEnd('/') + path)
                }
            },
        )
        NewsStatus("RSS-Bridge works the same way: paste the generated feed URL. A self-hosted Miniflux uses those URLs too.")
        if (note.isNotBlank()) NewsStatus(note)
        CATALOG.map { it.group }.distinct().forEach { group ->
            Text(
                text = group,
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            CATALOG.filter { it.group == group }.forEach { source ->
                val added = category.feeds.any { it.url == source.url }
                TvActionButton(
                    text = if (added) source.name else "Add ${source.name}",
                    icon = Icons.Rounded.RssFeed,
                    enabled = !added,
                    onClick = { add(source.name, source.url) },
                )
            }
        }
        DialTextField(
            label = "New category",
            value = newCategory,
            onValueChange = { newCategory = it },
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
            readOnly = false,
            placeholder = "Local",
            onDone = {
                val trimmed = newCategory.trim()
                if (trimmed.isNotEmpty()) onCreate(trimmed)
            },
        )
        TvActionButton(
            text = "Create category",
            icon = Icons.Rounded.Add,
            onClick = {
                val trimmed = newCategory.trim()
                if (trimmed.isNotEmpty()) onCreate(trimmed)
            },
        )
        TvActionButton(text = "Restore starter categories", icon = Icons.Rounded.Refresh, onClick = onRestore)
    }
}

@Composable
private fun NewsStatus(text: String) {
    Text(
        text = text,
        color = TvColors.TextSecondary,
        fontFamily = TvFonts.Body,
        fontSize = 16.sp,
        modifier = Modifier.widthIn(max = 920.dp),
    )
}

private fun openStory(context: Context, url: String): String? {
    if (url.isBlank()) return "This story has no link."
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Opened in the browser."
    } catch (_: ActivityNotFoundException) {
        "No browser on this device."
    }
}

private fun loadCategories(prefs: android.content.SharedPreferences): List<NewsCategory> {
    val raw = prefs.getString(NEWS_KEY, null) ?: return starterCategories()
    return decodeCategories(raw).ifEmpty { starterCategories() }
}

private fun starterCategories(): List<NewsCategory> = listOf(
    NewsCategory(
        "football",
        "Football",
        listOf(
            NewsFeed("bbc-football", "BBC Sport Football", "https://feeds.bbci.co.uk/sport/football/rss.xml"),
            NewsFeed("guardian-football", "Guardian Football", "https://www.theguardian.com/football/rss"),
            NewsFeed("sky-football", "Sky Sports Football", "https://www.skysports.com/rss/12040"),
        ),
    ),
    NewsCategory(
        "sport",
        "Sport",
        listOf(
            NewsFeed("bbc-sport", "BBC Sport", "https://feeds.bbci.co.uk/sport/rss.xml"),
            NewsFeed("guardian-sport", "Guardian Sport", "https://www.theguardian.com/uk/sport/rss"),
        ),
    ),
    NewsCategory(
        "world",
        "World",
        listOf(
            NewsFeed("bbc-news", "BBC News", "https://feeds.bbci.co.uk/news/rss.xml"),
            NewsFeed("aljazeera", "Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml"),
            NewsFeed("guardian-world", "Guardian World", "https://www.theguardian.com/world/rss"),
        ),
    ),
    NewsCategory(
        "tech",
        "Tech",
        listOf(
            NewsFeed("verge", "The Verge", "https://www.theverge.com/rss/index.xml"),
            NewsFeed("ars", "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index"),
            NewsFeed("hn", "Hacker News", "https://hnrss.org/frontpage"),
        ),
    ),
    NewsCategory(
        "local",
        "Local",
        listOf(NewsFeed("bbc-london", "BBC London", "https://feeds.bbci.co.uk/news/england/london/rss.xml")),
    ),
)

private fun encodeCategories(categories: List<NewsCategory>): String {
    val root = JSONArray()
    categories.forEach { category ->
        val feeds = JSONArray()
        category.feeds.forEach { feed ->
            feeds.put(JSONObject().put("id", feed.id).put("name", feed.name).put("url", feed.url))
        }
        root.put(JSONObject().put("id", category.id).put("name", category.name).put("feeds", feeds))
    }
    return root.toString()
}

private fun decodeCategories(raw: String): List<NewsCategory> = runCatching {
    val root = JSONArray(raw)
    buildList {
        for (index in 0 until root.length()) {
            val item = root.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            if (name.isEmpty()) continue
            val feedsJson = item.optJSONArray("feeds") ?: JSONArray()
            val feeds = buildList {
                for (feedIndex in 0 until feedsJson.length()) {
                    val feed = feedsJson.optJSONObject(feedIndex) ?: continue
                    val url = feed.optString("url").trim()
                    if (!allowedFeedUrl(url)) continue
                    val feedName = feed.optString("name").trim().ifBlank { hostOf(url) }
                    val id = feed.optString("id").ifBlank { slug(feedName) }
                    add(NewsFeed(id, feedName, url))
                }
            }
            add(NewsCategory(item.optString("id").ifBlank { slug(name) }, name.take(40), feeds))
        }
    }
}.getOrDefault(emptyList())

private fun slug(name: String): String {
    val base = name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "feed" }
    return "$base-${System.currentTimeMillis().toString(36)}"
}

private fun hostOf(url: String): String = runCatching {
    URI(url).host?.removePrefix("www.").orEmpty()
}.getOrDefault(url).ifBlank { url }

private fun allowedFeedUrl(raw: String): Boolean {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
    if (scheme != "http" && scheme != "https") return false
    val host = uri.host?.lowercase(Locale.US) ?: return false
    if (host == "localhost" || host.endsWith(".local") || host == "0.0.0.0" || host == "::1") return false
    val parts = host.split('.')
    if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
        val a = parts[0].toInt()
        val b = parts[1].toInt()
        if (a == 10 || a == 127 || a == 0) return false
        if (a == 169 && b == 254) return false
        if (a == 192 && b == 168) return false
        if (a == 172 && b in 16..31) return false
    }
    return true
}

private object NewsClient {
    data class Loaded(val stories: List<NewsStory>, val errors: List<String>)

    suspend fun load(feeds: List<NewsFeed>): Loaded = coroutineScope {
        val parts = feeds.map { feed ->
            async {
                runCatching { fetch(feed) }.fold(
                    onSuccess = { it to "" },
                    onFailure = { emptyList<NewsStory>() to (it.message ?: "${feed.name} didn't answer") },
                )
            }
        }.awaitAll()
        Loaded(
            stories = parts.flatMap { it.first }
                .distinctBy { it.link.ifBlank { it.title } }
                .sortedByDescending { it.time },
            errors = parts.map { it.second }.filter { it.isNotBlank() },
        )
    }

    private suspend fun fetch(feed: NewsFeed): List<NewsStory> = withContext(Dispatchers.IO) {
        if (!allowedFeedUrl(feed.url)) error("That source is not a public feed URL.")
        val connection = (java.net.URL(feed.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
            setRequestProperty("User-Agent", "ChudMAXXX/1.0")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("${feed.name} answered $code.")
            val xml = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                val body = StringBuilder()
                var total = 0
                while (total < 1_500_000) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    val take = minOf(read, 1_500_000 - total)
                    body.append(buffer, 0, take)
                    total += take
                }
                body.toString()
            }
            parseFeed(xml, feed)
        } finally {
            connection.disconnect()
        }
    }
}

private val ITEM_RE = Regex("(?i)<(item|entry)\\b[^>]*>([\\s\\S]*?)</\\1>")

private fun parseFeed(xml: String, feed: NewsFeed): List<NewsStory> {
    val trimmed = xml.removePrefix("\uFEFF").trim()
    if (!Regex("(?i)<(rss|feed|rdf:RDF|item|entry)\\b").containsMatchIn(trimmed)) {
        error("${feed.name} is not an RSS or Atom feed.")
    }
    val stories = ArrayList<NewsStory>()
    for (match in ITEM_RE.findAll(trimmed)) {
        if (stories.size >= 30) break
        val block = match.groupValues[2]
        val title = textOf(block, "title")
        if (title.isBlank()) continue
        val published = dateOf(block)
        val time = parseTime(published)
        stories += NewsStory(
            title = title,
            link = linkOf(block),
            summary = summaryOf(block),
            source = feed.name,
            whenLabel = if (time > 0L) {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
            } else {
                published
            },
            time = time,
        )
    }
    if (stories.isEmpty()) error("${feed.name} has no stories.")
    return stories
}

private fun textOf(block: String, tag: String): String {
    val match = Regex("(?i)<${Regex.escape(tag)}\\b[^>]*>([\\s\\S]*?)</${Regex.escape(tag)}>").find(block) ?: return ""
    return clean(match.groupValues[1])
}

private fun linkOf(block: String): String {
    val text = textOf(block, "link")
    if (text.startsWith("http://") || text.startsWith("https://")) return text
    val tag = Regex("(?i)<link\\b[^>]*>").find(block)?.value.orEmpty()
    return attr(tag, "href").ifBlank { text }
}

private fun summaryOf(block: String): String {
    val raw = textOf(block, "description").ifBlank { textOf(block, "summary") }.ifBlank { textOf(block, "content") }
    return raw.take(420)
}

private fun dateOf(block: String): String =
    textOf(block, "pubDate").ifBlank { textOf(block, "published") }
        .ifBlank { textOf(block, "updated") }
        .ifBlank { textOf(block, "dc:date") }

private fun attr(tag: String, name: String): String {
    val match = Regex("(?i)\\b${Regex.escape(name)}\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").find(tag) ?: return ""
    return decode(match.groupValues[2].ifBlank { match.groupValues[3] })
}

private fun clean(value: String): String = decode(value).replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

private fun decode(value: String): String {
    val unwrapped = value.replace(Regex("<!\\[CDATA\\[([\\s\\S]*?)]]>", RegexOption.IGNORE_CASE), "$1")
    var out = unwrapped
        .replace(Regex("&#(\\d+);")) { safeCode(it.groupValues[1].toIntOrNull() ?: -1) }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { safeCode(it.groupValues[1].toIntOrNull(16) ?: -1) }
    val named = listOf("lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "amp" to "&")
    for ((name, ch) in named) out = out.replace("&$name;", ch)
    return out
}

private fun safeCode(code: Int): String =
    if (code in 0..0x10FFFF) runCatching { Character.toChars(code).concatToString() }.getOrDefault("") else ""

private fun parseTime(raw: String): Long {
    if (raw.isBlank()) return 0L
    val cleaned = raw
        .replace(Regex("\\bBST\\b"), "+0100")
        .replace(Regex("\\bGMT\\b"), "+0000")
        .replace(Regex("\\bUTC\\b"), "+0000")
    val patterns = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss",
    )
    for (pattern in patterns) {
        val parsed = runCatching { SimpleDateFormat(pattern, Locale.US).parse(cleaned) }.getOrNull()
        if (parsed != null) return parsed.time
    }
    return 0L
}
