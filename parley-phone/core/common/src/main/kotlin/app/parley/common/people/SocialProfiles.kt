package app.parley.common.people

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * Social and professional profiles (Instagram, LinkedIn, X, GitHub…), kept as the contact's **website rows**: the
 * profile's https address with the service's name as the row's custom label ("Instagram"). Every contacts app shows a
 * labelled website and opens it (Google Contacts, Samsung, iOS through `itemN.URL` + `X-ABLabel`), Google and CardDAV
 * sync it, and the vCard engine already round-trips it, whereas a data row of Parley's own would be dropped by sync
 * adapters and shown by no other app.
 *
 * A website row is a profile when its label names a service, or (for rows other apps wrote) when its address is a
 * profile address of a known service. [ProfileService.label] is the English brand name stored as the label; brand
 * names aren't translated.
 */
enum class ProfileService(
    val key: String,
    val label: String,
    /** An example handle shown in the empty field. */
    val placeholder: String,
    /** Shown with an "@" in front ("@ana"), as the service itself writes it. */
    val at: Boolean,
    /** A short mark for the service's badge (Parley ships no brand logos). */
    val mark: String,
) {
    INSTAGRAM("instagram", "Instagram", "ana.lima", true, "Ig"),
    LINKEDIN("linkedin", "LinkedIn", "ana-lima-123", false, "in"),
    X("x", "X (Twitter)", "ana_lima", true, "X"),
    FACEBOOK("facebook", "Facebook", "ana.lima", false, "f"),
    TIKTOK("tiktok", "TikTok", "ana.lima", true, "Tk"),
    YOUTUBE("youtube", "YouTube", "@analima", false, "Yt"),
    SNAPCHAT("snapchat", "Snapchat", "ana-lima", false, "Sc"),
    THREADS("threads", "Threads", "ana.lima", true, "@"),
    BLUESKY("bluesky", "Bluesky", "ana.bsky.social", true, "Bs"),
    MASTODON("mastodon", "Mastodon", "ana@mastodon.social", true, "M"),
    GITHUB("github", "GitHub", "analima", false, "Gh"),
    REDDIT("reddit", "Reddit", "ana_lima", false, "R"),
    PINTEREST("pinterest", "Pinterest", "analima", false, "P"),
    TWITCH("twitch", "Twitch", "analima", false, "Tw"),
    BEHANCE("behance", "Behance", "analima", false, "Be"),
    DRIBBBLE("dribbble", "Dribbble", "analima", false, "Dr"),
    ;

    companion object {
        fun byKey(key: String?): ProfileService? = entries.firstOrNull { it.key == key }

        /** The service a website row's label names ("Instagram", "Twitter", "linkedin"…), or null. */
        fun byLabel(label: String?): ProfileService? {
            val l = label?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.label.lowercase(Locale.ROOT) == l || it.key == l } ?: ALIASES[l]
        }

        private val ALIASES = mapOf(
            "twitter" to X, "x" to X, "x.com" to X, "ig" to INSTAGRAM, "fb" to FACEBOOK, "youtube channel" to YOUTUBE,
            "bsky" to BLUESKY, "fediverse" to MASTODON, "snap" to SNAPCHAT,
        )
    }
}

/** One profile: [handle] is the service's own form ("ana.lima", "@analima" on YouTube, "ana@mastodon.social"). */
data class Profile(val service: ProfileService, val handle: String) {
    /** The https profile address, which the installed app (if any) opens through its app links, else the browser. */
    val url: String get() = SocialProfiles.url(service, handle)

    /** As shown: "@ana.lima", "analima", "u/ana_lima", "@ana@mastodon.social". */
    val display: String get() = SocialProfiles.display(service, handle)
}

/** What looks wrong in a typed handle: not the service's form, or a Mastodon name without its server. */
enum class ProfileProblem { FORMAT, NEEDS_SERVER }

object SocialProfiles {
    /** Android's Website.TYPE_CUSTOM: the row's label then says which service it is. */
    const val TYPE_CUSTOM = 0

    /** Services offered first in the "Add profile" list (the most used); the rest follow. */
    val common: List<ProfileService> = listOf(
        ProfileService.INSTAGRAM, ProfileService.LINKEDIN, ProfileService.X, ProfileService.FACEBOOK, ProfileService.TIKTOK,
        ProfileService.YOUTUBE, ProfileService.GITHUB, ProfileService.BLUESKY, ProfileService.MASTODON, ProfileService.THREADS,
    )

    /**
     * The profile a website row holds: by its label ([type] custom, label naming a service) or, for any label, by an
     * address of a known service. Null for an ordinary website.
     */
    fun fromWebsite(value: String, type: Int?, label: String?): Profile? {
        val byLabel = if (type == TYPE_CUSTOM) ProfileService.byLabel(label) else null
        val v = value.trim()
        if (byLabel == null) return fromUrl(v)
        // A link under a service's label that isn't a profile of it (a post, another site): what its address says.
        if (looksLikeUrl(v)) return handleFromUrl(byLabel, v)?.let { Profile(byLabel, it) } ?: fromUrl(v)
        return Profile(byLabel, normalize(byLabel, v))
    }

    /** Whether a website row is kept as a profile by its label (how Parley writes them). */
    fun labelled(type: Int?, label: String?): ProfileService? = if (type == TYPE_CUSTOM) ProfileService.byLabel(label) else null

    /** A profile address of a known service ("https://www.instagram.com/ana.lima/?igsh=…"), or null. */
    fun fromUrl(raw: String): Profile? {
        val (host, segments, query) = split(raw) ?: return null
        val service = serviceOfHost(host) ?: return null
        val handle = handleFromPath(service, host, segments, query) ?: return null
        return Profile(service, handle).takeIf { problem(service, handle) == null }
    }

    /**
     * Tidies what was typed or pasted for [service] into its handle: a pasted profile address gives its handle, a
     * leading "@" goes (YouTube keeps it, it is part of the handle there), "u/" goes on Reddit. Never invents content;
     * an empty or unparseable input comes back trimmed.
     */
    @Suppress("CyclomaticComplexMethod") // One rule per service.
    fun normalize(service: ProfileService, raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        // A pasted address: of this service (any Mastodon server), or of another one (left for the caller to see).
        if (looksLikeUrl(s)) handleFromUrl(service, s)?.let { return it }
        s = s.trimEnd('/')
        return when (service) {
            ProfileService.YOUTUBE -> when {
                s.startsWith("@") -> s
                s.startsWith("channel/") || s.startsWith("c/") || s.startsWith("user/") -> s
                else -> "@$s"
            }
            ProfileService.MASTODON -> s.removePrefix("@")
            ProfileService.REDDIT -> s.removePrefix("/").removePrefix("u/").removePrefix("user/").removePrefix("@")
            ProfileService.LINKEDIN -> s.removePrefix("/").removePrefix("in/").removePrefix("@")
            ProfileService.BLUESKY -> s.removePrefix("@").let { if ('.' in it || it.startsWith("did:")) it else if (it.isEmpty()) it else "$it.bsky.social" }
            ProfileService.FACEBOOK -> s.removePrefix("@")
            else -> s.removePrefix("@")
        }
    }

    /** The https profile address of [handle] on [service]. */
    @Suppress("CyclomaticComplexMethod") // One address form per service.
    fun url(service: ProfileService, handle: String): String {
        val h = handle.trim()
        return when (service) {
            ProfileService.INSTAGRAM -> "https://www.instagram.com/$h/"
            ProfileService.LINKEDIN ->
                if (h.startsWith("company/") || h.startsWith("school/")) "https://www.linkedin.com/$h/" else "https://www.linkedin.com/in/$h/"
            ProfileService.X -> "https://x.com/$h"
            ProfileService.FACEBOOK -> if (h.all { it.isDigit() }) "https://www.facebook.com/profile.php?id=$h" else "https://www.facebook.com/$h"
            ProfileService.TIKTOK -> "https://www.tiktok.com/@$h"
            ProfileService.YOUTUBE -> "https://www.youtube.com/$h"
            ProfileService.SNAPCHAT -> "https://www.snapchat.com/add/$h"
            ProfileService.THREADS -> "https://www.threads.net/@$h"
            ProfileService.BLUESKY -> "https://bsky.app/profile/$h"
            ProfileService.MASTODON -> {
                val user = h.substringBefore('@')
                val server = h.substringAfter('@', "")
                if (server.isEmpty()) "" else "https://$server/@$user"
            }
            ProfileService.GITHUB -> "https://github.com/$h"
            ProfileService.REDDIT -> "https://www.reddit.com/user/$h"
            ProfileService.PINTEREST -> "https://www.pinterest.com/$h/"
            ProfileService.TWITCH -> "https://www.twitch.tv/$h"
            ProfileService.BEHANCE -> "https://www.behance.net/$h"
            ProfileService.DRIBBBLE -> "https://dribbble.com/$h"
        }
    }

    /** The website row's value for what was typed: the profile address, or "" while nothing usable is typed. */
    fun valueFor(service: ProfileService, typed: String): String {
        val h = normalize(service, typed)
        return if (h.isEmpty()) "" else url(service, h)
    }

    fun display(service: ProfileService, handle: String): String = when {
        handle.isEmpty() -> ""
        service == ProfileService.REDDIT -> "u/$handle"
        service == ProfileService.LINKEDIN -> handle.removePrefix("company/").removePrefix("school/")
        service.at -> "@$handle"
        else -> handle
    }

    private val instagramName = Regex("[A-Za-z0-9._]{1,30}")
    private val xName = Regex("[A-Za-z0-9_]{1,15}")
    private val facebookName = Regex("[A-Za-z0-9.\\-]{1,50}|\\d{5,20}")
    private val tiktokName = Regex("[A-Za-z0-9_.]{2,24}")
    private val youtubeName = Regex("@[A-Za-z0-9._\\-]{3,30}|channel/UC[A-Za-z0-9_\\-]{22}|(c|user)/[A-Za-z0-9._\\-]{1,100}")
    private val snapchatName = Regex("[A-Za-z][A-Za-z0-9._\\-]{2,14}")
    private val blueskyName = Regex("([A-Za-z0-9]([A-Za-z0-9\\-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,}|did:[a-z]+:[A-Za-z0-9._:%\\-]+")
    private val mastodonName = Regex("[A-Za-z0-9_]{1,30}@([A-Za-z0-9\\-]+\\.)+[A-Za-z]{2,}")
    private val githubName = Regex("[A-Za-z0-9](-?[A-Za-z0-9]){0,38}")
    private val redditName = Regex("[A-Za-z0-9_\\-]{3,20}")
    private val linkedinName = Regex("((company|school)/)?[\\p{L}\\p{N}\\-_.%]{2,100}")
    private val pinterestName = Regex("[A-Za-z0-9_]{3,30}")
    private val twitchName = Regex("[A-Za-z0-9_]{3,25}")
    private val plainName = Regex("[A-Za-z0-9_\\-.]{2,40}")

    /** Why [typed] doesn't look like a handle of [service] (worded under the field), or null. Saving is never blocked. */
    @Suppress("CyclomaticComplexMethod") // One pattern per service.
    fun problem(service: ProfileService, typed: String): ProfileProblem? {
        val h = normalize(service, typed)
        if (h.isEmpty()) return null
        if (service == ProfileService.MASTODON && '@' !in h) return ProfileProblem.NEEDS_SERVER
        val ok = when (service) {
            ProfileService.INSTAGRAM, ProfileService.THREADS -> instagramName.matches(h)
            ProfileService.LINKEDIN -> linkedinName.matches(h)
            ProfileService.X -> xName.matches(h)
            ProfileService.FACEBOOK -> facebookName.matches(h)
            ProfileService.TIKTOK -> tiktokName.matches(h)
            ProfileService.YOUTUBE -> youtubeName.matches(h)
            ProfileService.SNAPCHAT -> snapchatName.matches(h)
            ProfileService.BLUESKY -> blueskyName.matches(h)
            ProfileService.MASTODON -> mastodonName.matches(h)
            ProfileService.GITHUB -> githubName.matches(h)
            ProfileService.REDDIT -> redditName.matches(h)
            ProfileService.PINTEREST -> pinterestName.matches(h)
            ProfileService.TWITCH -> twitchName.matches(h)
            ProfileService.BEHANCE, ProfileService.DRIBBBLE -> plainName.matches(h)
        }
        return if (ok) null else ProfileProblem.FORMAT
    }

    /** Words Contacts search should also find a profile by: its handle with and without "@" ("ana.lima", "@ana.lima"). */
    fun searchTerms(p: Profile): List<String> = listOf(p.handle, p.display).filter { it.isNotEmpty() }.distinct()

    /**
     * A vCard `X-SOCIALPROFILE` (iOS, vCard 3) or `SOCIALPROFILE` (RFC 9554) as a profile: [type] is its TYPE or
     * SERVICE-TYPE ("twitter", "linkedin"…), [user] its `X-USER` / `USERNAME`, [value] the address (or the handle).
     * Null when nothing usable is there.
     */
    fun fromSocialProfile(type: String?, user: String?, value: String?): Profile? {
        val v = value?.trim().orEmpty().replace("\\:", ":")
        fromUrl(v)?.let { return it }
        val service = ProfileService.byLabel(type) ?: return null
        // The address first (it names a Mastodon server), then the user name, then whatever the value holds.
        val candidates = listOfNotNull(v.takeIf { looksLikeUrl(it) }, user, v.removePrefix("x-apple:"))
        return candidates.map { normalize(service, it) }.firstOrNull { it.isNotEmpty() && problem(service, it) == null }?.let { Profile(service, it) }
    }

    // ---- Addresses

    /** The handle in a profile address of [service] (any server for Mastodon), or null. */
    private fun handleFromUrl(service: ProfileService, url: String): String? {
        val (host, segments, query) = split(url) ?: return null
        if (serviceOfHost(host) != service && service != ProfileService.MASTODON) return null
        return handleFromPath(service, host, segments, query)
    }

    private fun looksLikeUrl(s: String): Boolean =
        s.contains("://") || Regex("^(www\\.|m\\.)?[A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)+/").containsMatchIn(s)

    /** Host (lower-case, without www./m./mobile./country prefixes), path segments and query of an address. */
    private fun split(raw: String): Triple<String, List<String>, Map<String, String>>? {
        val s = raw.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val withScheme = if (s.contains("://")) s else "https://$s"
        val uri = runCatching { URI(withScheme.replace(" ", "%20")) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        var host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        for (p in listOf("www.", "m.", "mobile.", "web.", "old.", "new.", "np.")) if (host.startsWith(p)) host = host.removePrefix(p)
        val path = runCatching { uri.rawPath?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") } }.getOrNull() ?: uri.path.orEmpty()
        val segments = path.split('/').filter { it.isNotEmpty() }
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=', "")
            if (k.isEmpty()) null else k to kv.substringAfter('=')
        }.toMap()
        return Triple(host, segments, query)
    }

    private val pinterestHost = Regex("([a-z]{2}\\.)?pinterest\\.[a-z.]{2,6}")
    private val linkedinHost = Regex("([a-z]{2}\\.)?linkedin\\.com")

    /** Big Mastodon servers, so their addresses are recognised without a label (any server works with one). */
    private val MASTODON_SERVERS = setOf(
        "mastodon.social", "mastodon.online", "mstdn.social", "mas.to", "fosstodon.org", "hachyderm.io", "infosec.exchange",
        "mastodon.world", "techhub.social", "universeodon.com", "mastodon.cloud", "mstdn.jp", "troet.cafe", "chaos.social",
    )

    @Suppress("CyclomaticComplexMethod") // One host rule per service.
    private fun serviceOfHost(host: String): ProfileService? = when {
        host == "instagram.com" || host == "instagr.am" -> ProfileService.INSTAGRAM
        linkedinHost.matches(host) -> ProfileService.LINKEDIN
        host == "x.com" || host == "twitter.com" -> ProfileService.X
        host == "facebook.com" || host == "fb.com" || host == "fb.me" -> ProfileService.FACEBOOK
        host == "tiktok.com" -> ProfileService.TIKTOK
        host == "youtube.com" -> ProfileService.YOUTUBE
        host == "snapchat.com" -> ProfileService.SNAPCHAT
        host == "threads.net" || host == "threads.com" -> ProfileService.THREADS
        host == "bsky.app" -> ProfileService.BLUESKY
        host in MASTODON_SERVERS -> ProfileService.MASTODON
        host == "github.com" -> ProfileService.GITHUB
        host == "reddit.com" -> ProfileService.REDDIT
        pinterestHost.matches(host) -> ProfileService.PINTEREST
        host == "twitch.tv" -> ProfileService.TWITCH
        host == "behance.net" -> ProfileService.BEHANCE
        host == "dribbble.com" -> ProfileService.DRIBBBLE
        else -> null
    }

    // Paths that are pages of the service, not people.
    private val INSTAGRAM_PAGES = setOf("p", "reel", "reels", "tv", "explore", "accounts", "direct", "about", "legal", "developer")
    private val X_PAGES = setOf(
        "home", "i", "intent", "search", "share", "explore", "notifications", "messages", "settings", "hashtag", "compose", "login", "signup",
        "tos", "privacy",
    )
    private val FACEBOOK_PAGES = setOf(
        "groups", "events", "watch", "sharer", "sharer.php", "share", "story.php", "photo.php", "photo", "login", "marketplace", "gaming",
        "help", "pages", "hashtag", "reel", "dialog",
    )
    private val GITHUB_PAGES = setOf(
        "features", "topics", "about", "settings", "marketplace", "explore", "login", "join", "notifications", "pulls", "issues", "search",
        "sponsors", "collections", "trending", "enterprise", "pricing", "apps",
    )
    private val PINTEREST_PAGES = setOf("pin", "search", "ideas", "today", "business", "categories", "explore")
    private val TWITCH_PAGES = setOf("directory", "videos", "p", "settings", "downloads", "search", "jobs")
    private val DRIBBBLE_PAGES = setOf("shots", "jobs", "search", "designers", "tags", "stories", "pro", "session", "signup")

    @Suppress("CyclomaticComplexMethod") // One small rule per service.
    private fun handleFromPath(service: ProfileService, host: String, seg: List<String>, query: Map<String, String>): String? {
        val first = seg.firstOrNull()
        val h: String? = when (service) {
            ProfileService.INSTAGRAM -> when {
                first == "stories" -> seg.getOrNull(1)
                first != null && first !in INSTAGRAM_PAGES -> first
                else -> null
            }
            ProfileService.THREADS -> first?.takeIf { it.startsWith("@") }?.removePrefix("@")
            ProfileService.TIKTOK -> first?.takeIf { it.startsWith("@") }?.removePrefix("@")
            ProfileService.X -> first?.takeIf { it.lowercase(Locale.ROOT) !in X_PAGES }?.removePrefix("@")
            ProfileService.FACEBOOK -> when {
                first == "profile.php" -> query["id"]?.takeIf { id -> id.all { it.isDigit() } }
                first == "people" -> seg.getOrNull(2)?.takeIf { id -> id.all { it.isDigit() } }
                first != null && first.lowercase(Locale.ROOT) !in FACEBOOK_PAGES -> first
                else -> null
            }
            ProfileService.YOUTUBE -> when {
                first == null -> null
                first.startsWith("@") -> first
                first == "channel" || first == "c" || first == "user" -> seg.getOrNull(1)?.let { "$first/$it" }
                else -> null
            }
            ProfileService.SNAPCHAT -> when {
                first == "add" -> seg.getOrNull(1)
                first?.startsWith("@") == true -> first.removePrefix("@")
                else -> null
            }
            ProfileService.BLUESKY -> if (first == "profile") seg.getOrNull(1) else null
            ProfileService.MASTODON -> when {
                first?.startsWith("@") == true && !first.substring(1).contains('@') -> first.removePrefix("@") + "@" + host
                first?.startsWith("@") == true -> first.removePrefix("@")
                first == "users" -> seg.getOrNull(1)?.let { "$it@$host" }
                else -> null
            }
            ProfileService.GITHUB -> when {
                first == "orgs" -> seg.getOrNull(1)
                first != null && first.lowercase(Locale.ROOT) !in GITHUB_PAGES -> first
                else -> null
            }
            ProfileService.REDDIT -> if (first == "user" || first == "u") seg.getOrNull(1) else null
            ProfileService.LINKEDIN -> when (first) {
                "in" -> seg.getOrNull(1)
                "company", "school" -> seg.getOrNull(1)?.let { "$first/$it" }
                else -> null
            }
            ProfileService.PINTEREST -> first?.takeIf { it.lowercase(Locale.ROOT) !in PINTEREST_PAGES }
            ProfileService.TWITCH -> first?.takeIf { it.lowercase(Locale.ROOT) !in TWITCH_PAGES }
            ProfileService.BEHANCE -> first?.takeIf { it != "gallery" && it != "search" }
            ProfileService.DRIBBBLE -> first?.takeIf { it.lowercase(Locale.ROOT) !in DRIBBBLE_PAGES }
        }
        return h?.trim()?.takeIf { it.isNotEmpty() }
    }
}
