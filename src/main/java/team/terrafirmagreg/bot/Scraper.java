package team.terrafirmagreg.bot;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Scraper for the TerraFirmaGreg Field Guide website.
 */
public class Scraper {

    // const BASE = 'https://terrafirmacraft.github.io/Field-Guide/';
    public static final String BASE = "https://terrafirmagreg-team.github.io/Field-Guide-Modern/";

    private static final int EMBED_DESC_LIMIT = 4096;
    // Lines starting with these labels are considered metadata and excluded from embeds.
    private static final Pattern STAT_PREFIX_RE = Pattern.compile("^(Recipe:|Multiblock:)", Pattern.CASE_INSENSITIVE);

    // Fragments containing these substrings will be ignored.
    private static final List<String> FRAGMENT_BLACKLIST_SUBSTRINGS = List.of(
            "glb-viewer",
            "nav-primary",
            "navbar-content",
            "lang-dropdown-button",
            "bd-theme",
            "bd-theme-text"
    );

    /** Default TTL for cached search index (ms). */
    private static final long INDEX_TTL_MS = 10 * 60 * 1000;

    private static final Map<String, CachedIndex> cachedIndexByLang = new ConcurrentHashMap<>();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static final Gson gson = new Gson();

    /**
     * Checks whether an id or URL contains any blacklisted substrings.
     * @param idOrUrl A fragment id or URL.
     * @return True if blacklisted.
     */
    private static boolean isBlacklistedFragment(String idOrUrl) {
        try {
            if (idOrUrl == null || idOrUrl.isEmpty()) return false;
            String candidate = idOrUrl.contains("#") ? idOrUrl.substring(idOrUrl.lastIndexOf('#') + 1) : idOrUrl;
            String lc = candidate.toLowerCase();
            return FRAGMENT_BLACKLIST_SUBSTRINGS.stream().anyMatch(lc::contains);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * If text exceeds limit, truncate and add ellipsis.
     * Doesnt seem to work all the time tho :(
     * @param text The input text.
     * @param limit Maximum characters allowed.
     * @return Truncated string with ellipsis.
     */
    private static String truncateWithEllipsis(String text, int limit) {
        if (text == null) return text;
        if (text.length() <= limit) return text;
        String ellipsis = "...";
        int sliceLen = Math.max(0, limit - ellipsis.length());
        return text.substring(0, sliceLen) + ellipsis;
    }

    private static String truncateWithEllipsis(String text) {
        return truncateWithEllipsis(text, EMBED_DESC_LIMIT);
    }

    /**
     * Converts an UL/OL element into a text list.
     * @param listEl UL/OL element.
     * @param ordered True for ordered list.
     * @return List items joined by newlines.
     */
    private static String getListText(Element listEl, boolean ordered, String currentUrl) {
        List<String> lines = new ArrayList<>();
        Elements items = listEl.children().select("li");
        int index = 1;
        for (Element li : items) {
            String t = getInlineMarkdown(li, currentUrl);
            String clean = t != null ? t.trim() : "";
            if (!clean.isEmpty()) {
                lines.add((ordered ? (index++ + ".") : "-") + " " + clean);
            }
        }
        return String.join("\n", lines);
    }

    /**
     * Converts element children to inline Discord markdown.
     */
    private static String getInlineMarkdown(Element el, String currentUrl) {
        StringBuilder out = new StringBuilder();
        for (Node child : el.childNodes()) {
            if (child instanceof TextNode) {
                out.append(((TextNode) child).text());
            } else if (child instanceof Element) {
                Element c = (Element) child;
                String tag = c.tagName().toLowerCase();
                switch (tag) {
                    case "br" -> out.append("\n");
                    case "strong", "b" -> {
                        String inner = getInlineMarkdown(c, currentUrl);
                        if (!inner.isEmpty()) out.append("**").append(inner).append("**");
                    }
                    case "em", "i" -> {
                        String inner = getInlineMarkdown(c, currentUrl);
                        if (!inner.isEmpty()) out.append("*").append(inner).append("*");
                    }
                    case "code", "kbd" -> {
                        String inner = getInlineMarkdown(c, currentUrl).replace("`", "\u200B`");
                        if (!inner.isEmpty()) out.append("`").append(inner).append("`");
                    }
                    case "a" -> {
                        String href = c.attr("href");
                        String text = getInlineMarkdown(c, currentUrl);
                        if (text.isEmpty()) text = href;
                        try {
                            String abs = "";
                            String baseForResolve = currentUrl != null ? currentUrl : BASE;
                            String langForLink = Locales.DEFAULT_LANG;
                            try {
                                URL u = new URL(baseForResolve);
                                String[] parts = u.getPath().split("/");
                                int rootIdx = -1;
                                for (int i = 0; i < parts.length; i++) {
                                    if ("Field-Guide-Modern".equals(parts[i]) || "Field-Guide".equals(parts[i])) {
                                        rootIdx = i;
                                        break;
                                    }
                                }
                                if (rootIdx != -1 && rootIdx + 1 < parts.length) {
                                    String maybe = parts[rootIdx + 1];
                                    if (Locales.LANGS.contains(maybe)) langForLink = maybe;
                                }
                            } catch (Exception ignored) {}

                            if (href == null || href.isEmpty()) {
                                abs = "";
                            } else if (href.startsWith("#")) {
                                abs = ensureLang(new URL(new URL(baseForResolve), href).toString(), langForLink);
                            } else if (href.matches("^https?://.*")) {
                                abs = canonicalLangHtml(href, langForLink);
                            } else {
                                abs = canonicalLangHtml(new URL(new URL(baseForResolve), href).toString(), langForLink);
                            }
                            if (!abs.isEmpty()) {
                                out.append("[").append(text).append("](").append(abs).append(")");
                            } else {
                                out.append(text);
                            }
                        } catch (Exception e) {
                            out.append(text);
                        }
                    }
                    default -> out.append(getInlineMarkdown(c, currentUrl));
                }
            }
        }
        return out.toString();
    }

    /**
     * Detects if a node represents breadcrumb navigation.
     * * Why does everything web related sound like food?
     */
    private static boolean isBreadcrumb(Element el) {
        String tag = el.tagName().toLowerCase();
        if ("nav".equals(tag)) return true;
        String aria = el.attr("aria-label").toLowerCase();
        if (aria.contains("breadcrumb")) return true;
        String cls = el.attr("class").toLowerCase();
        return cls.contains("breadcrumb");
    }

    /**
     * Returns true if the element is inside any of the given selectors.
     * Useful for excluding UI/recipe containers from text extraction.
     */
    private static boolean isWithin(Element el, String selector) {
        try {
            return el.closest(selector) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Determines if an element should be included in text.
     * @return True for P/UL/OL in main content.
     */
    private static boolean shouldIncludeNode(Element el) {
        String tag = el.tagName().toLowerCase();
        if (tag.isEmpty()) return false;
        if (isBreadcrumb(el)) return false;
        // Exclude crafting/utility UI blocks entirely
        if (isWithin(el, ".crafting-recipe, .minecraft-text, .item-header, .glb-viewer, .glb-viewer-container")) return false;
        if (tag.startsWith("h")) return false;
        return tag.equals("p") || tag.equals("ul") || tag.equals("ol");
    }

    /**
     * Converts an element into plain text, handling lists specially.
     * @return Text content or empty string if skipped.
     */
    private static String nodeToText(Element el, String currentUrl) {
        String tag = el.tagName().toLowerCase();
        if (isBreadcrumb(el)) return "";
        String cls = el.attr("class").toLowerCase();
        if (cls.contains("crafting-recipe-item-count")) return "";
        if (isWithin(el, ".crafting-recipe, .minecraft-text, .item-header, .glb-viewer, .glb-viewer-container")) return "";
        if (tag.startsWith("h")) return "";
        if (tag.equals("ul")) return getListText(el, false, currentUrl);
        if (tag.equals("ol")) return getListText(el, true, currentUrl);
        String t = getInlineMarkdown(el, currentUrl).trim();
        if (STAT_PREFIX_RE.matcher(t).find()) return "";
        if (t.matches("^\\d+$")) return "";
        return t;
    }

    /**
     * Normalizes a string into lowercases and underscores.
     */
    private static String normalizeId(String str) {
        if (str == null) return "";
        String normalized = Normalizer.normalize(str, Normalizer.Form.NFKD);
        return normalized
                .toLowerCase()
                .replaceAll("[\\u0300-\\u036f]", "")
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .replaceAll("_+", "_");
    }

    /**
     * Ensures URLs contain the selected locale segment.
     */
    public static String ensureLang(String url, String lang) {
        try {
            String safe = Locales.LANGS.contains(lang) ? lang : Locales.DEFAULT_LANG;
            URL u = new URL(url);
            String[] parts = u.getPath().split("/");
            List<String> partsList = new ArrayList<>(Arrays.asList(parts));

            int rootIdx = -1;
            for (int i = 0; i < partsList.size(); i++) {
                if ("Field-Guide-Modern".equals(partsList.get(i)) || "Field-Guide".equals(partsList.get(i))) {
                    rootIdx = i;
                    break;
                }
            }

            if (rootIdx != -1) {
                if (rootIdx + 1 < partsList.size()) {
                    String next = partsList.get(rootIdx + 1);
                    if (Locales.LANGS.contains(next)) {
                        if (!next.equals(safe)) partsList.set(rootIdx + 1, safe);
                    } else {
                        partsList.add(rootIdx + 1, safe);
                    }
                } else {
                    partsList.add(safe);
                }
                String newPath = String.join("/", partsList);
                return new URL(u.getProtocol(), u.getHost(), u.getPort(), newPath + (u.getQuery() != null ? "?" + u.getQuery() : "") + (u.getRef() != null ? "#" + u.getRef() : "")).toString();
            }
            return url;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Sets a URL to HTML, removes hash, and enforces selected lang.
     */
    public static String canonicalLangHtml(String url, String lang) {
        try {
            String ensured = ensureLang(url, lang);
            URL u = new URL(ensured);
            String path = u.getPath();
            String[] pathParts = path.split("/");
            String last = pathParts.length > 0 ? pathParts[pathParts.length - 1] : "";

            if (last.isEmpty()) {
                path = path + (path.endsWith("/") ? "" : "/") + "index.html";
            } else if (!last.contains(".")) {
                path = path + (path.endsWith("/") ? "" : "/") + "index.html";
            } else if ("index".equals(last)) {
                path = path.replaceAll("/index$", "/index.html");
            }

            return new URL(u.getProtocol(), u.getHost(), u.getPort(), path + (u.getQuery() != null ? "?" + u.getQuery() : "")).toString();
        } catch (Exception e) {
            return ensureLang(url, lang);
        }
    }

    /**
     * Parses a path/URL into a base URL and optional fragment id.
     * @return Array with baseUrl at index 0 and fragment (or null) at index 1.
     */
    public static String[] parsePathAndFragment(String path, String lang) {
        try {
            if (path.matches("^https?://.*")) {
                URL u = new URL(path);
                String useLang = lang;
                String[] parts = u.getPath().split("/");
                int rootIdx = -1;
                for (int i = 0; i < parts.length; i++) {
                    if ("Field-Guide-Modern".equals(parts[i]) || "Field-Guide".equals(parts[i])) {
                        rootIdx = i;
                        break;
                    }
                }
                if (rootIdx != -1 && rootIdx + 1 < parts.length) {
                    String maybe = parts[rootIdx + 1];
                    if (Locales.LANGS.contains(maybe)) useLang = maybe;
                }
                String baseUrl = canonicalLangHtml(u.toString().split("#")[0], useLang);
                String fragment = u.getRef();
                return new String[]{baseUrl, fragment};
            }

            String[] split = path.split("#", 2);
            String p = split[0];
            String frag = split.length > 1 ? split[1] : null;
            String normalized = p.replaceAll("^/*|/*$", "");
            String endsHtml = normalized.endsWith(".html") ? normalized : normalized + ".html";
            String safeLang = Locales.LANGS.contains(lang) ? lang : Locales.DEFAULT_LANG;
            String langBase = BASE + safeLang + "/";
            String baseUrl = canonicalLangHtml(langBase + endsHtml, safeLang);
            return new String[]{baseUrl, frag};
        } catch (Exception e) {
            return new String[]{path, null};
        }
    }

    /**
     * Builds a full URL from a relative path or absolute URL, preserving fragment.
     */
    public static String buildUrlFromPath(String path, String lang) {
        String[] parsed = parsePathAndFragment(path, lang);
        String baseUrl = parsed[0];
        String fragment = parsed[1];
        return fragment != null ? baseUrl + "#" + fragment : baseUrl;
    }

    /**
     * Builds the search index URL from BASE or uses an override.
     * * Thank you Yan :3
     */
    private static String buildSearchIndexUrlForLang(String lang, String override) {
        if (override != null && !override.isEmpty()) return override;
        String envOverride = System.getenv("SEARCH_INDEX_URL");
        if (envOverride != null && !envOverride.isEmpty()) return envOverride;
        String safeLang = Locales.LANGS.contains(lang) ? lang : Locales.DEFAULT_LANG;
        return BASE + safeLang + "/search_index.json";
    }

    /**
     * Fetches and caches the search index.
     */
    private static List<SearchIndexEntry> fetchSearchIndexForLang(String lang, String override) throws Exception {
        long now = System.currentTimeMillis();
        CachedIndex cache = cachedIndexByLang.get(lang);
        if (cache != null && (now - cache.timestamp) < INDEX_TTL_MS) {
            return cache.data;
        }

        String url = buildSearchIndexUrlForLang(lang, override);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Cache-Control", "no-cache")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        List<SearchIndexEntry> data = gson.fromJson(response.body(), new TypeToken<List<SearchIndexEntry>>(){}.getType());
        if (data == null) throw new RuntimeException("Invalid search_index.json format for " + lang);
        cachedIndexByLang.put(lang, new CachedIndex(data, now));
        return data;
    }

    /**
     * Sets a query string into lowercase terms.
     */
    private static List<String> tokenize(String q) {
        if (q == null || q.isEmpty()) return Collections.emptyList();
        String processed = q.toLowerCase()
                .replaceAll("[_#./-]+", " ")
                .replaceAll("[^\\p{L}\\p{N}\\s]", "")
                .trim();
        return Arrays.stream(processed.split("\\s+"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Escapes a string.
     */
    private static String escapeForRegex(String s) {
        return Pattern.quote(s);
    }

    /**
     * Checks if a term appears as a standalone word in text.
     * Word boundaries are defined by transitions.
     */
    private static boolean hasStandaloneTerm(String text, String term) {
        if (text == null || term == null || text.isEmpty() || term.isEmpty()) return false;
        String esc = escapeForRegex(term);
        // regex moment.
        Pattern re = Pattern.compile("(?:^|[^\\p{L}\\p{N}])" + esc + "(?:[^\\p{L}\\p{N}]|$)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
        return re.matcher(text).find();
    }

    /**
     * Compares a search index row against query terms.
     */
    private static int scoreEntry(SearchIndexEntry entry, List<String> terms) {
        String title = entry.entry != null ? entry.entry : "";
        String content = entry.content != null ? entry.content : "";
        int score = 0;
        for (String t : terms) {
            if (hasStandaloneTerm(title, t)) score += 4;
            if (hasStandaloneTerm(content, t)) score += 2;
        }
        for (String t : terms) {
            String esc = escapeForRegex(t);
            Pattern reStart = Pattern.compile("^(?:" + esc + ")(?:[^\\p{L}\\p{N}]|$)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
            if (reStart.matcher(title).find()) score += 1;
        }
        return score;
    }

    /**
     * Searches the JSON index and returns matches as URLs with titles.
     */
    public static List<SearchResult> searchGuideViaIndex(String query, String selectedLang, String searchIndexUrl, int limit) {
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) return Collections.emptyList();

        String effectiveLang = Locales.LANGS.contains(selectedLang) ? selectedLang : Locales.DEFAULT_LANG;

        List<ScoredResult> combined = new ArrayList<>();
        for (String lang : Locales.LANGS) {
            List<SearchIndexEntry> idx;
            try {
                idx = fetchSearchIndexForLang(lang, searchIndexUrl);
            } catch (Exception e) {
                continue;
            }
            for (SearchIndexEntry e : idx) {
                int s = scoreEntry(e, terms);
                if (s > 0) {
                    combined.add(new ScoredResult(s, e.entry != null ? e.entry : "Field Guide", buildUrlFromPath(e.url, lang), lang));
                }
            }
        }

        combined.sort((a, b) -> Integer.compare(b.score, a.score));

        Set<String> seen = new HashSet<>();
        List<SearchResult> top = new ArrayList<>();
        int cap = Math.max(1, Math.min(limit, 500));

        for (ScoredResult r : combined) {
            if (!r.lang.equals(effectiveLang)) continue;
            String abs = r.url;
            if (!seen.contains(abs)) {
                seen.add(abs);
                top.add(new SearchResult(r.title, abs));
            }
            if (top.size() >= cap) break;
        }
        return top;
    }

    /**
     * Try JSON index first then fallback to BFS search.
     */
    public static List<SearchResult> searchGuideFast(String query, String selectedLang, int limit) {
        try {
            List<SearchResult> viaIndex = searchGuideViaIndex(query, selectedLang, null, limit);
            if (!viaIndex.isEmpty()) return viaIndex;
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

    /**
     * Fetches HTML content for a given URL.
     */
    private static Document fetchHtml(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return Jsoup.parse(response.body(), url);
    }

    /**
     * Extracts the first image src.
     */
    private static String extractFirstImage(Document doc, Element root) {
        Element scope = root != null ? root : doc.body();
        Element img = scope.selectFirst("img");
        if (img == null) return null;
        String src = img.attr("src");
        if (src == null || src.isEmpty()) return null;
        if (src.startsWith("http")) return src;
        try {
            return new URL(new URL(BASE), src).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractFirstImage(Document doc) {
        return extractFirstImage(doc, null);
    }

    /**
     * Extracts a title from the page (prefers h1/h2).
     */
    private static String extractTitle(Document doc) {
        Element h1 = doc.selectFirst("h1");
        if (h1 != null && !h1.text().trim().isEmpty()) return h1.text().trim();
        Element h2 = doc.selectFirst("h2");
        if (h2 != null && !h2.text().trim().isEmpty()) return h2.text().trim();
        String title = doc.title().trim();
        return !title.isEmpty() ? title : "Field Guide";
    }

    /**
     * Fetches a page and returns its localized title and URL.
     */
    public static SearchResult fetchPageTitle(String urlOrPath, String lang) {
        try {
            String[] parsed = parsePathAndFragment(urlOrPath, lang);
            String baseUrl = parsed[0];
            Document doc = fetchHtml(baseUrl);
            String title = extractTitle(doc);
            return new SearchResult(!title.isEmpty() ? title : "Field Guide", baseUrl);
        } catch (Exception e) {
            String[] parsed = parsePathAndFragment(urlOrPath, lang);
            return new SearchResult("Field Guide", parsed[0]);
        }
    }

    /**
     * Builds a short intro by scanning main content blocks near the title.
     */
    private static String extractSummaryIntro(Document doc, String pageTitle, String currentUrl) {
        Elements headers = doc.select("h1, h2, h3");
        for (Element header : headers) {
            if (header.text().trim().equals(pageTitle)) {
                String id = header.attr("id");
                if (id != null && !id.isEmpty() && !isBlacklistedFragment(id)) {
                    SectionData sect = extractSection(doc, id, currentUrl);
                    if (sect != null && sect.description != null && !sect.description.isEmpty()) {
                        return sect.description;
                    }
                }
                break;
            }
        }

        Element root = doc.selectFirst(".col-md-9");
        Element scope = root != null ? root : doc.body();
        List<String> blocks = new ArrayList<>();
        int currentLen = 0;
        int sepLen = 2;

        for (Element el : scope.select("p, ul, ol")) {
            String t = nodeToText(el, currentUrl);
            if (t == null || t.isEmpty()) continue;
            if (STAT_PREFIX_RE.matcher(t).find()) continue;
            int addLen = (blocks.isEmpty() ? 0 : sepLen) + t.length();
            if (currentLen + addLen > EMBED_DESC_LIMIT) break;
            blocks.add(t);
            currentLen += addLen;
        }

        String text = String.join("\n\n", blocks);
        return truncateWithEllipsis(text);
    }

    /**
     * Extracts a section's text and image.
     */
    private static SectionData extractSection(Document doc, String fragmentId, String currentUrl) {
        if (fragmentId == null || fragmentId.isEmpty()) return null;
        if (isBlacklistedFragment(fragmentId)) return null;

        Element el = doc.getElementById(fragmentId);
        if (el == null) return null;

        String tag = el.tagName().toLowerCase();
        Integer level = null;
        if (tag.startsWith("h")) {
            try {
                level = Integer.parseInt(tag.substring(1));
            } catch (NumberFormatException e) {
                level = 6;
            }
        }

        List<String> parts = new ArrayList<>();
        Element cursor = el.nextElementSibling();
        final Integer finalLevel = level;

        while (cursor != null) {
            String tagC = cursor.tagName().toLowerCase();
            if (tagC.startsWith("h")) {
                int lvl;
                try {
                    lvl = Integer.parseInt(tagC.substring(1));
                } catch (NumberFormatException e) {
                    lvl = 6;
                }
                if (finalLevel != null && lvl <= finalLevel) break;
            }

            Element contentRoot = el.closest(".col-md-9");
            Element cursorForCheck = cursor;
            if (contentRoot != null && contentRoot.select("*").stream().noneMatch(e -> e.equals(cursorForCheck))) break;
            if (isBreadcrumb(cursor)) break;

            if (tagC.startsWith("h")) {
                int lvl;
                try {
                    lvl = Integer.parseInt(tagC.substring(1));
                } catch (NumberFormatException e) {
                    lvl = 6;
                }
                if (finalLevel == null || lvl > finalLevel) {
                    String text = cursor.text().trim();
                    if (!text.isEmpty()) parts.add("**" + text + "**");
                }
                cursor = cursor.nextElementSibling();
                continue;
            }

            if (!shouldIncludeNode(cursor)) {
                cursor = cursor.nextElementSibling();
                continue;
            }

            String txt = nodeToText(cursor, currentUrl);
            if (txt != null && !txt.isEmpty()) parts.add(txt);
            cursor = cursor.nextElementSibling();
            if (String.join("\n\n", parts).length() > EMBED_DESC_LIMIT) break;
        }

        Element scope = el.parent();
        String image = extractFirstImage(doc, scope);
        String title = !el.text().trim().isEmpty() ? el.text().trim() : fragmentId;
        String normalizedTitle = normalizeId(title);
        String pageTitleNorm = normalizeId(extractTitle(doc));

        List<String> cleaned = new ArrayList<>();
        Set<String> seenNorms = new HashSet<>();

        for (String block : parts) {
            String pt = block.trim();
            if (pt.isEmpty()) continue;
            String norm = normalizeId(pt);
            // Drop exact duplicates of section or page title.
            if (norm.equals(normalizedTitle) || norm.equals(pageTitleNorm)) continue;
            // Drop near-duplicates.
            if (cleaned.isEmpty()) {
                if ((norm.startsWith(normalizedTitle) && pt.length() <= title.length() + 15) ||
                        (norm.startsWith(pageTitleNorm) && pt.length() <= extractTitle(doc).length() + 15)) {
                    continue;
                }
            }
            if (seenNorms.contains(norm)) continue;
            seenNorms.add(norm);
            cleaned.add(pt);
        }

        String desc = truncateWithEllipsis(String.join("\n\n", cleaned));
        return new SectionData(title, desc, image);
    }

    /**
     * Builds a simple table of contents from h2/h3 elements, excluding blacklisted ids.
     */
    private static List<TocItem> buildToc(Document doc, String baseUrl, String pageTitle) {
        List<TocItem> items = new ArrayList<>();
        Elements headers = doc.select("h2[id], h3[id]");

        for (Element el : headers) {
            String id = el.attr("id");
            String txt = el.text().trim();
            if (id.isEmpty() || txt.isEmpty()) continue;
            if (isBlacklistedFragment(id)) continue;
            if (txt.equals(pageTitle)) continue;
            String url = baseUrl + "#" + id;
            items.add(new TocItem(txt, url));
        }

        Set<String> seen = new HashSet<>();
        List<TocItem> unique = new ArrayList<>();
        for (TocItem it : items) {
            String key = normalizeId(it.title) + "#" + it.url.substring(it.url.lastIndexOf('#') + 1);
            if (seen.contains(key)) continue;
            seen.add(key);
            unique.add(it);
            if (unique.size() >= 60) break;
        }
        return unique;
    }

    /**
     * Builds a Discord embed for a full page or a specific section if a fragment is provided.
     * * Fragment sections dont work well yet.
     */
    public static MessageEmbed fetchGuideEmbed(String urlOrPath, String lang) throws IOException, InterruptedException {
        String[] parsed = parsePathAndFragment(urlOrPath, lang);
        String baseUrl = parsed[0];
        String fragment = parsed[1];

        Document doc = fetchHtml(baseUrl);
        String title = extractTitle(doc);
        String description = fragment != null ? null : extractSummaryIntro(doc, title, baseUrl);
        String image = extractFirstImage(doc);

        if (fragment != null) {
            SectionData sect = extractSection(doc, fragment, baseUrl);
            if (sect != null) {
                EmbedBuilder embed = new EmbedBuilder()
                        .setTitle(sect.title + " — " + title, baseUrl + "#" + fragment)
                        .setDescription(truncateWithEllipsis(sect.description != null && !sect.description.isEmpty() ? sect.description : "Open the page for details."))
                        .setColor(0x3AA3FF);
                if (sect.image != null) embed.setThumbnail(sect.image);
                else if (image != null) embed.setThumbnail(image);
                return embed.build();
            }
        }

        List<TocItem> toc = buildToc(doc, baseUrl, title);
        List<String> tocLines = toc.stream()
                .map(it -> "- [" + it.title + "](" + it.url + ")")
                .collect(Collectors.toList());

        String baseText = description != null ? description.trim() : "";
        int baseLen = baseText.length();
        int remaining = Math.max(0, EMBED_DESC_LIMIT - (baseLen > 0 ? baseLen + 2 : 0));

        List<String> pickedToc = new ArrayList<>();
        int used = 0;
        for (String line : tocLines) {
            int add = (pickedToc.isEmpty() ? 0 : 1) + line.length() + 1;
            if (used + add > remaining) break;
            pickedToc.add(line);
            used += add;
        }

        List<String> parts = new ArrayList<>();
        if (!baseText.isEmpty()) parts.add(baseText);
        if (!pickedToc.isEmpty()) {
            if (!parts.isEmpty()) parts.add("");
            parts.addAll(pickedToc);
        }
        String combined = String.join("\n", parts).trim();
        String withEllipsis = truncateWithEllipsis(combined);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(title, baseUrl)
                .setDescription(!withEllipsis.isEmpty() ? withEllipsis : "Open the page for details.")
                .setColor(0x3AA3FF);

        if (image != null) embed.setThumbnail(image);
        return embed.build();
    }

    // Helper classes
    private static class CachedIndex {
        List<SearchIndexEntry> data;
        long timestamp;

        CachedIndex(List<SearchIndexEntry> data, long timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }
    }

    public static class SearchIndexEntry {
        public String entry;
        public String content;
        public String url;
    }

    public static class SearchResult {
        public String title;
        public String url;

        public SearchResult(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    private static class ScoredResult {
        int score;
        String title;
        String url;
        String lang;

        ScoredResult(int score, String title, String url, String lang) {
            this.score = score;
            this.title = title;
            this.url = url;
            this.lang = lang;
        }
    }

    private static class SectionData {
        String title;
        String description;
        String image;

        SectionData(String title, String description, String image) {
            this.title = title;
            this.description = description;
            this.image = image;
        }
    }

    private static class TocItem {
        String title;
        String url;

        TocItem(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }
}
// Thank you for listening to my TED talk.

