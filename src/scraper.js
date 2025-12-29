
  const axios = require('axios');
  const cheerio = require('cheerio');
  const { EmbedBuilder } = require('discord.js');

  const BASE = 'https://terrafirmagreg-team.github.io/Field-Guide-Modern/en_us/';
  const EMBED_DESC_LIMIT = 4096;
  // Lines starting with these labels are considered metadata and excluded from embeds.
  const STAT_PREFIX_RE = /^(Recipe:|Multiblock:)/i;

  // Fragments containing these substrings will be ignored.
  const FRAGMENT_BLACKLIST_SUBSTRINGS = [
    'glb-viewer',
    'nav-primary',
    'navbar-content',
    'lang-dropdown-button',
    'bd-theme',
    'bd-theme-text',
    'glb-viewer'
  ];

  /**
   * Checks whether an id or URL contains any blacklisted substrings.
   * @param {string} idOrUrl - A fragment id or URL.
   * @returns {boolean} True if blacklisted.
   */
  function isBlacklistedFragment(idOrUrl) {
    try {
      if (!idOrUrl) return false;
      const candidate = idOrUrl.includes('#') ? idOrUrl.split('#').pop() : idOrUrl;
      const lc = candidate.toLowerCase();
      return FRAGMENT_BLACKLIST_SUBSTRINGS.some(sub => lc.includes(sub));
    } catch {
      return false;
    };
  };

  /**
   * If text exceeds limit, truncate and add ellipsis.
   * Doesnt seem to work all the time tho :(
   * @param {string} text - The input text.
   * @param {number} [limit=EMBED_DESC_LIMIT] - Maximum characters allowed.
   * @returns {string} Truncated string with ellipsis.
   */
  function truncateWithEllipsis(text, limit = EMBED_DESC_LIMIT) {
    if (!text) return text;
    if (text.length <= limit) return text;
    const ellipsis = '...';
    const sliceLen = Math.max(0, limit - ellipsis.length);
    return text.slice(0, sliceLen) + ellipsis;
  };

  /**
   * Converts an UL/OL element into a text list.
   * @param {import('cheerio').CheerioAPI} $ - Cheerio instance.
   * @param {import('cheerio').Element} listEl - UL/OL element.
   * @param {boolean} ordered - True for ordered list.
   * @returns {string} List items joined by newlines.
   */
  function getListText($, listEl, ordered) {
    const lines = [];
    $(listEl).children('li').each((i, li) => {
      const t = getInlineMarkdown($, $(li));
      const clean = (t || '').trim();
      if (clean) lines.push(`${ordered ? `${i + 1}.` : '-' } ${clean}`);
    });
    return lines.join('\n');
  };

  /**
   * Converts element children to inline Discord markdown.
   * @param {import('cheerio').CheerioAPI} $
   * @param {import('cheerio').Cheerio<import('cheerio').Element>} el
   * @returns {string}
   */
  function getInlineMarkdown($, el) {
    let out = '';
    const children = el.contents();
    children.each((_, child) => {
      if (child.type === 'text') {
        out += String(child.data || '');
        return;
      };
      if (child.type === 'tag') {
        const tag = (child.name || '').toLowerCase();
        const $c = $(child);
        if (tag === 'br') { out += '\n'; return; }
        if (tag === 'strong' || tag === 'b') { const inner = getInlineMarkdown($, $c); out += inner ? `**${inner}**` : ''; return; };
        if (tag === 'em' || tag === 'i') { const inner = getInlineMarkdown($, $c); out += inner ? `*${inner}*` : ''; return; };
        if (tag === 'code' || tag === 'kbd') { const inner = getInlineMarkdown($, $c).replace(/`/g, '\u200B`'); out += inner ? `\`${inner}\`` : ''; return; };
        if (tag === 'a') {
          const href = $c.attr('href') || '';
          const text = getInlineMarkdown($, $c) || href;
          try { const abs = href ? new URL(href, BASE).toString() : ''; out += abs ? `[${text}](${abs})` : text; }
          catch { out += text; }
          return;
        };
        out += getInlineMarkdown($, $c);
      };
    });
    return out;
  };

  /**
   * Detects if a node represents breadcrumb navigation.
   * * Why does everything web related sound like food?
   * @param {import('cheerio').CheerioAPI} $ - Cheerio instance.
   * @param {import('cheerio').Cheerio<import('cheerio').Element>} el - Element to test.
   * @returns {boolean} True if breadcrumb.
   */
  function isBreadcrumb($, el) {
    const tag = $(el).prop('tagName')?.toLowerCase();
    if (tag === 'nav') return true;
    const aria = ($(el).attr('aria-label') || '').toLowerCase();
    if (aria.includes('breadcrumb')) return true;
    const cls = ($(el).attr('class') || '').toLowerCase();
    if (cls.includes('breadcrumb')) return true;
    return false;
  };

  /**
   * Returns true if the element is inside any of the given selectors.
   * Useful for excluding UI/recipe containers from text extraction.
   * @param {import('cheerio').CheerioAPI} $ - Cheerio instance.
   * @param {import('cheerio').Cheerio<import('cheerio').Element>} el - Element to test.
   * @param {string} selector - CSS selectors.
   * @returns {boolean}
   */
  function isWithin($, el, selector) {
    try {
      const c = $(el).closest(selector);
      return !!(c && c.length);
    } catch {
      return false;
    };
  };

  /**
   * Determines if an element should be included in text.
   * @param {import('cheerio').CheerioAPI} $ - Cheerio instance.
   * @param {import('cheerio').Cheerio<import('cheerio').Element>} el - Element to check.
   * @returns {boolean} True for P/UL/OL in main content.
   */
  function shouldIncludeNode($, el) {
    const tag = $(el).prop('tagName')?.toLowerCase();
    if (!tag) return false;
    if (isBreadcrumb($, el)) return false;
    // Exclude crafting/utility UI blocks entirely
    if (isWithin($, el, '.crafting-recipe, .minecraft-text, .item-header, .glb-viewer, .glb-viewer-container')) return false;
    if (tag.startsWith('h')) return false;
    return tag === 'p' || tag === 'ul' || tag === 'ol';
  };

  /**
   * Converts an element into plain text, handling lists specially.
   * @param {import('cheerio').CheerioAPI} $ - Cheerio instance.
   * @param {import('cheerio').Cheerio<import('cheerio').Element>} el - Element to convert.
   * @returns {string} Text content or empty string if skipped.
   */
  function nodeToText($, el) {
    const tag = $(el).prop('tagName')?.toLowerCase();
    if (isBreadcrumb($, el)) return '';
    const cls = ($(el).attr('class') || '').toLowerCase();
    if (cls.includes('crafting-recipe-item-count')) return '';
    if (isWithin($, el, '.crafting-recipe, .minecraft-text, .item-header, .glb-viewer, .glb-viewer-container')) return '';
    if (tag && tag.startsWith('h')) return '';
    if (tag === 'ul') return getListText($, el, false);
    if (tag === 'ol') return getListText($, el, true);
    const t = getInlineMarkdown($, $(el)).trim();
    if (STAT_PREFIX_RE.test(t)) return '';
    if (/^\d+$/.test(t)) return '';
    return t;
  };

  /**
   * Normalizes a string into lowercases and underscores.
   * @param {string} str - String to normalize.
   * @returns {string} Normalized string.
   */
  function normalizeId(str) {
    return (str || '')
      .toLowerCase()
      .normalize('NFKD').replace(/[\u0300-\u036f]/g, '')
      .replace(/[^a-z0-9]+/g, '_')
      .replace(/^_+|_+$/g, '')
      .replace(/_+/g, '_');
  };

  /**
   * Ensures URLs contain '/en_us/' after 'Field-Guide-Modern'.
   * * Sorry, just too slow to search multiple languages.
   * @param {string} url - Input URL.
   * @returns {string} URL with en_us.
   */
  function ensureEnUs(url) {
    try {
      const u = new URL(url);
      const parts = u.pathname.split('/');
      const i = parts.indexOf('Field-Guide-Modern');
      if (i !== -1) {
        const next = parts[i + 1];
        if (next !== 'en_us') {
          parts.splice(i + 1, 0, 'en_us');
          u.pathname = parts.join('/');
        };
      };
      return u.toString();
    } catch {
      return url;
    };
  };

  /**
   * Produces an en_us URL (ensures index.html and removes hash).
   * @param {string} url - Input URL.
   * @returns {string} Output URL.
   */
  function canonicalEnUsHtml(url) {
    try {
      const u = new URL(ensureEnUs(url));
      u.hash = '';
      const path = u.pathname;
      const last = path.split('/').pop();
      if (!last || last === '') {
        if (!u.pathname.endsWith('/')) u.pathname += '/';
        u.pathname += 'index.html';
      } else if (!last.includes('.')) {
        if (!u.pathname.endsWith('/')) u.pathname += '/';
        u.pathname += 'index.html';
      } else if (last === 'index') {
        u.pathname = u.pathname.replace(/\/index$/, '/index.html');
      };
      return u.toString();
    } catch {
      return ensureEnUs(url);
    };
  };

  /**
   * Parses a path/URL into a base URL and optional fragment id.
   * @param {string} path - Relative path or absolute URL (may include '#fragment').
   * @returns {{ baseUrl: string, fragment: string|null }} Output components.
   */
  function parsePathAndFragment(path) {
    if (/^https?:\/\//i.test(path)) {
      const u = new URL(path);
      const baseUrl = canonicalEnUsHtml(u.toString());
      const fragment = u.hash ? u.hash.slice(1) : null;
      return { baseUrl, fragment };
    };
    const [p, frag] = String(path).split('#');
    const normalized = p.replace(/^\/*|\/*$/g, '');
    const endsHtml = normalized.endsWith('.html') ? normalized : `${normalized}.html`;
    const baseUrl = canonicalEnUsHtml(`${BASE}${endsHtml}`);
    return { baseUrl, fragment: frag || null };
  };

  /**
   * Builds a full URL from a relative path or absolute URL, preserving fragment.
   * @param {string} path - Relative path or absolute URL.
   * @returns {string} Finished URL.
   */
  function buildUrlFromPath(path) {
    const { baseUrl, fragment } = parsePathAndFragment(path);
    return fragment ? `${baseUrl}#${fragment}` : baseUrl;
  };

  /** Default TTL for cached search index (ms). */
  const INDEX_TTL_MS = 10 * 60 * 1000;
  /** @type {Array<{ entry: string, content?: string, url: string }>|null} */
  let _cachedIndex = null;
  let _cachedIndexAt = 0;

  /**
   * Builds the search index URL from BASE or uses an override.
   * * Thank you Yan :3
   * @param {string|undefined} override URL to search_index.json
   * @returns {string}
   */
  function buildSearchIndexUrl(override) {
    if (override) return override;
    const envOverride = process.env.SEARCH_INDEX_URL;
    if (envOverride) return envOverride;
    return BASE.endsWith('/') ? `${BASE}search_index.json` : `${BASE}/search_index.json`;
  };

  /**
   * Fetches and caches the search index.
   * @param {string|undefined} override URL to search_index.json
   * @returns {Promise<Array<{ entry: string, content?: string, url: string }>>}
   */
  async function fetchSearchIndex(override) {
    const now = Date.now();
    if (_cachedIndex && (now - _cachedIndexAt) < INDEX_TTL_MS) return _cachedIndex;
    const url = buildSearchIndexUrl(override);
    const res = await axios.get(url, { headers: { 'Cache-Control': 'no-cache' }, timeout: 15000 });
    if (!Array.isArray(res.data)) throw new Error('Invalid search_index.json format');
    _cachedIndex = res.data;
    _cachedIndexAt = now;
    return _cachedIndex;
  };

  /**
   * Sets a query string into lowercase terms.
   * @param {string} q
   * @returns {string[]}
   */
  function tokenize(q) {
    return (q || '')
      .toLowerCase()
      .replace(/[_#./-]+/g, ' ')
      .replace(/[^\p{L}\p{N}\s]/gu, '')
      .trim()
      .split(/\s+/)
      .filter(Boolean);
  };

  /**
   * Escapes a string.
   * @param {string} s
   * @returns {string}
   */
  function escapeForRegex(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  };

  /**
   * Checks if a term appears as a standalone word in text.
   * Word boundaries are defined by transitions.
   * @param {string} text
   * @param {string} term
   * @returns {boolean}
   */
  function hasStandaloneTerm(text, term) {
    if (!text || !term) return false;
    const esc = escapeForRegex(term);
    // regex moment.
    const re = new RegExp(`(?:^|[^\\p{L}\\p{N}])${esc}(?:[^\\p{L}\\p{N}]|$)`, 'iu');
    return re.test(text);
  };

  /**
   * Checks if haystack contains needle. (real terms btw).
   * @param {string} hay
   * @param {string} needle
   * @returns {boolean}
   */
  function normalizedHasToken(hay, needle) {
    const h = normalizeId(hay);
    const n = normalizeId(needle);
    if (!n) return false;
    const re = new RegExp(`(?:^|_)${escapeForRegex(n)}(?:_|$)`);
    return re.test(h);
  };

  /**
   * Compares a search index row against query terms.
   * @param {{ entry: string, content?: string }} entry
   * @param {string[]} terms
   * @returns {number}
   */
  function scoreEntry(entry, terms) {
    const title = (entry.entry || '');
    const content = (entry.content || '');
    let score = 0;
    for (const t of terms) {
      if (hasStandaloneTerm(title, t)) score += 4;
      if (hasStandaloneTerm(content, t)) score += 2;
    };
    for (const t of terms) {
      const esc = escapeForRegex(t);
      const reStart = new RegExp(`^(?:${esc})(?:[^\\p{L}\\p{N}]|$)`, 'iu');
      if (reStart.test(title)) score += 1;
    };
    return score;
  };

  /**
   * Searches the JSON index and returns matches as URLs with titles.
   * @param {string} query
   * @param {{ searchIndexUrl?: string, limit?: number }} [opts]
   * @returns {Promise<Array<{ title: string, url: string }>>}
   */
  async function searchGuideViaIndex(query, opts) {
    const terms = tokenize(query);
    if (!terms.length) return [];
    const idx = await fetchSearchIndex(opts?.searchIndexUrl);
    const scored = [];
    for (const e of idx) {
      const s = scoreEntry(e, terms);
      if (s > 0) scored.push({ s, title: e.entry || 'Field Guide', url: e.url });
    };
    scored.sort((a, b) => b.s - a.s);

    const seen = new Set();
    const top = [];
    const cap = Math.max(1, Math.min(opts?.limit ?? 250, 500));
    for (const r of scored) {
      const abs = buildUrlFromPath(r.url);
      if (!seen.has(abs)) {
        seen.add(abs);
        top.push({ title: r.title, url: abs });
      };
      if (top.length >= cap) break;
    };
    return top;
  };

  /**
   * Try JSON index first then fallback to BFS search.
   * @param {string} query
   * @param {{ searchIndexUrl?: string, limit?: number }} [opts]
   * @returns {Promise<Array<{ title: string, url: string }>>}
   */
  async function searchGuideFast(query, opts) {
    try {
      const viaIndex = await searchGuideViaIndex(query, opts);
      if (viaIndex.length) return viaIndex;
    } catch {}
    const limit = opts?.limit ?? 25;
    const fallback = await searchGuideNormalizedNoCache(query, 800, limit);
    return fallback;
  };

  /**
   * Fetches HTML content for a given URL.
   * @param {string} url - The page URL.
   * @returns {Promise<string>} Resolved HTML string.
   */
  async function fetchHtml(url) {
    const resp = await axios.get(url, { timeout: 15000 });
    return resp.data;
  };

  /**
   * Extracts the first image src.
   * @param {import('cheerio').CheerioAPI} $ - Cheerio instance.
   * @param {import('cheerio').Cheerio<import('cheerio').Element>} [root] - Scope to search.
   * @returns {string|null} Image URL or null if not found.
   */
  function extractFirstImage($, root) {
    const scope = root || $.root();
    const img = scope.find('img').first();
    const src = img.attr('src');
    if (!src) return null;
    if (src.startsWith('http')) return src;
    return new URL(src, BASE).toString();
  };

  /**
   * Extracts a title from the page (prefers h1/h2).
   * @param {import('cheerio').CheerioAPI} $ - Cheerio instance.
   * @returns {string} Page title.
   */
  function extractTitle($) {
    const h1 = $('h1').first().text().trim();
    if (h1) return h1;
    const h2 = $('h2').first().text().trim();
    if (h2) return h2;
    const title = $('title').text().trim();
    return title || 'Field Guide';
  };

  /**
   * Builds a short intro by scanning main content blocks near the title.
   * @param {import('cheerio').CheerioAPI} $ - Cheerio instance.
   * @param {string} pageTitle - The current page title.
   * @returns {string} Summary/intro text.
   */
  function extractSummaryIntro($, pageTitle) {
    const header = $('h1, h2, h3').filter((i, el) => $(el).text().trim() === pageTitle).first();
    if (header && header.length) {
      const id = header.attr('id');
      if (id && !isBlacklistedFragment(id)) {
        const sect = extractSection($, id);
        if (sect && sect.description) return sect.description;
      };
    };
    const root = $('.col-md-9');
    const scope = root.length ? root : $.root();
    const blocks = [];
    let currentLen = 0;
    const sepLen = 2;
    scope.find('p, ul, ol').each((i, el) => {
      const t = nodeToText($, el);
      if (!t) return;
      if (STAT_PREFIX_RE.test(t)) return;
      const addLen = (blocks.length ? sepLen : 0) + t.length;
      if (currentLen + addLen > EMBED_DESC_LIMIT) {
        return false;
      };
      blocks.push(t);
      currentLen += addLen;
    });
    const text = blocks.join('\n\n');
    return truncateWithEllipsis(text, EMBED_DESC_LIMIT);
  };

  /**
   * Extracts a section's text and image.
   * @param {import('cheerio').CheerioAPI} $ - Cheerio instance.
   * @param {string} fragmentId - The heading id to start from.
   * @returns {{ title: string, description: string, image: string|null }|null} Section data or null.
   */
  function extractSection($, fragmentId) {
    if (!fragmentId) return null;
    if (isBlacklistedFragment(fragmentId)) return null;
    const el = $(`#${fragmentId}`).first();
    if (!el || el.length === 0) return null;
    const tag = el.prop('tagName')?.toLowerCase();
    const level = tag && tag.startsWith('h') ? parseInt(tag.slice(1), 10) || 6 : null;
    const parts = [];
    let cursor = el.next();
    const stop = (node) => {
      const t = node.prop('tagName')?.toLowerCase();
      if (!t) return false;
      if (!t.startsWith('h')) return false;
      const lvl = parseInt(t.slice(1), 10) || 6;
      return level ? lvl <= level : false;
    };
    while (cursor && cursor.length) {
      if (stop(cursor)) break;
      // Ensure we stay within main content and skip breadcrumbs/nav.
      const contentRoot = el.closest('.col-md-9');
      if (contentRoot && contentRoot.length && contentRoot.find(cursor).length === 0) break;
      if (isBreadcrumb($, cursor)) break;
      const tagC = cursor.prop('tagName')?.toLowerCase();
      if (tagC && tagC.startsWith('h')) {
        const lvl = parseInt(tagC.slice(1), 10) || 6;
        if (!level || lvl > level) {
          const text = cursor.text().trim();
          if (text) parts.push(`**${text}**`);
        }
        cursor = cursor.next();
        continue;
      }
      if (!shouldIncludeNode($, cursor)) {
        cursor = cursor.next();
        continue;
      };
      const txt = nodeToText($, cursor);
      if (txt) parts.push(txt);
      cursor = cursor.next();
      if (parts.join('\n\n').length > EMBED_DESC_LIMIT) break;
    };
    const scope = el.parent();
    const image = extractFirstImage($, scope);
    const title = el.text().trim() || fragmentId;
    const normalizedTitle = normalizeId(title);
    const pageTitleNorm = normalizeId(extractTitle($));
    const cleaned = [];
    const seenNorms = new Set();
    for (const block of parts) {
      const pt = (block || '').trim();
      if (!pt) continue;
      const norm = normalizeId(pt);
      // Drop exact duplicates of section or page title.
      if (norm === normalizedTitle || norm === pageTitleNorm) continue;
      // Drop near-duplicates.
      if (!cleaned.length) {
        if ((norm.startsWith(normalizedTitle) && pt.length <= title.length + 15) ||
            (norm.startsWith(pageTitleNorm) && pt.length <= (extractTitle($) || '').length + 15)) {
          continue;
        };
      };
      if (seenNorms.has(norm)) continue;
      seenNorms.add(norm);
      cleaned.push(pt);
    };
    const desc = truncateWithEllipsis(cleaned.join('\n\n'), EMBED_DESC_LIMIT);
    return { title, description: desc, image };
  };

  /**
   * Builds a simple table of contents from h2/h3 elements, excluding blacklisted ids.
   * @param {import('cheerio').CheerioAPI} $ - Cheerio instance.
   * @param {string} baseUrl - Base page URL for links.
   * @param {string} pageTitle - The page title to avoid duplicating.
   * @returns {Array<{ title: string, url: string }>} TOC items.
   */
  function buildToc($, baseUrl, pageTitle) {
    const items = [];
    $('h2[id], h3[id]').each((i, el) => {
      const id = $(el).attr('id');
      const txt = $(el).text().trim();
      if (!id || !txt) return;
      if (isBlacklistedFragment(id)) return;
      if (txt === pageTitle) return;
      const url = `${baseUrl}#${id}`;
      items.push({ title: txt, url });
    });
    const seen = new Set();
    const unique = [];
    for (const it of items) {
      const key = `${normalizeId(it.title)}#${it.url.split('#').pop()}`;
      if (seen.has(key)) continue;
      seen.add(key);
      unique.push(it);
      if (unique.length >= 60) break;
    };
    return unique;
  }

  /**
   * Builds a Discord embed for a full page or a specific section if a fragment is provided.
   * * Fragment sections dont work well yet.
   * @param {string} urlOrPath - Absolute URL or relative path (may include '#fragment').
   * @returns {Promise<EmbedBuilder>} Discord embed.
   */
  async function fetchGuideEmbed(urlOrPath) {
    const { baseUrl, fragment } = parsePathAndFragment(urlOrPath);
    const html = await fetchHtml(baseUrl);
    const $ = cheerio.load(html);

    const title = extractTitle($);
    let description = fragment
      ? null
      : extractSummaryIntro($, title);
    let image = extractFirstImage($);

    if (fragment) {
      const sect = extractSection($, fragment);
      if (sect) {
        const embed = new EmbedBuilder()
          .setTitle(`${sect.title} — ${title}`)
          .setURL(`${baseUrl}#${fragment}`)
          .setDescription(truncateWithEllipsis(sect.description || 'Open the page for details.', EMBED_DESC_LIMIT))
          .setColor(0x3AA3FF);
        if (sect.image) embed.setThumbnail(sect.image);
        else if (image) embed.setThumbnail(image);
        return embed;
      };
    };

    const toc = buildToc($, baseUrl, title);
    const tocLines = toc.map(it => `- [${it.title}](${it.url})`);
    const baseText = (description || '').trim();
    const baseLen = baseText.length;
    const remaining = Math.max(0, EMBED_DESC_LIMIT - (baseLen ? baseLen + 2 : 0));
    const pickedToc = [];
    let used = 0;
    for (const line of tocLines) {
      const add = (pickedToc.length ? 1 : 0) + line.length + 1;
      if (used + add > remaining) break;
      pickedToc.push(line);
      used += add;
    };
    const combined = [baseText, pickedToc.length ? '' : null, ...pickedToc]
      .filter(Boolean)
      .join('\n')
      .trim();
    const withEllipsis = truncateWithEllipsis(combined, EMBED_DESC_LIMIT);

    const embed = new EmbedBuilder()
      .setTitle(title)
      .setURL(baseUrl)
      .setDescription(withEllipsis || 'Open the page for details.')
      .setColor(0x3AA3FF);

    if (image) embed.setThumbnail(image);
    return embed;
  };

  /**
   * Collects internal links under the en_us base from a page.
   * @param {string} currentHtml - HTML content of the current page.
   * @param {string} currentUrl - URL used to resolve relative links.
   * @returns {string[]} Internal links without fragments.
   */
  function collectLinksEnUs(currentHtml, currentUrl) {
    const $ = cheerio.load(currentHtml);
    const links = [];
    $('a[href]').each((i, el) => {
      const href = $(el).attr('href');
      if (!href) return;
      if (href.startsWith('#')) return;
      const resolved = href.startsWith('http') ? href : new URL(href, currentUrl).toString();
      if (!resolved.startsWith(BASE)) return;
      const noFrag = resolved.split('#')[0];
      links.push(canonicalEnUsHtml(noFrag));
    });
    return Array.from(new Set(links));
  };

  /**
   * Tests if a relative path or fragment loosely matches a query.
   * @param {string} relPath - Path relative to base (may include '#fragment').
   * @param {string} norm - Query string.
   * @returns {boolean} True if the filename or fragment contains the query.
   */
  function filenameAndFragmentMatch(relPath, norm) {
    try {
      const [pathPart, frag] = relPath.split('#');
      const segments = pathPart.split('/').filter(Boolean);
      const filename = segments.length ? segments[segments.length - 1] : pathPart;
      const base = filename.replace(/\.html$/i, '');
      if (normalizedHasToken(base, norm)) return true;
      if (frag && normalizedHasToken(frag, norm)) return true;
      return false;
    } catch {
      return false;
    };
  };

  /**
   * Parses all headings and anchors from a page into section entries.
   * @param {string} url - Page URL.
   * @returns {Promise<Array<{ id: string, title: string, url: string }>>} Sections found.
   */
  async function parseSectionsNoCache(url) {
    const normalizedUrl = canonicalEnUsHtml(url);
    try {
      const html = await fetchHtml(normalizedUrl);
      const $ = cheerio.load(html);
      const seen = new Set();
      const sections = [];
      $('h1, h2, h3, h4, h5, h6').each((i, el) => {
        const id = $(el).attr('id');
        const txt = $(el).text().trim();
        if (id && txt && !seen.has(id) && !isBlacklistedFragment(id)) {
          seen.add(id);
          sections.push({ id, title: txt, url: `${normalizedUrl}#${id}` });
        };
      });
      $('a[href^="#"]').each((i, el) => {
        const href = $(el).attr('href');
        const id = (href || '').slice(1);
        const txt = $(el).text().trim();
        if (id && txt && !seen.has(id) && !isBlacklistedFragment(id)) {
          seen.add(id);
          sections.push({ id, title: txt, url: `${normalizedUrl}#${id}` });
        };
      });
      $('[id]').each((i, el) => {
        const id = $(el).attr('id');
        if (!id || seen.has(id) || isBlacklistedFragment(id)) return;
        const txt = $(el).text().trim();
        if (txt && txt.length >= 2) {
          seen.add(id);
          sections.push({ id, title: txt.slice(0, 120), url: `${normalizedUrl}#${id}` });
        };
      });
      return sections;
    } catch {
      return [];
    };
  };

  /**
   * Performs a crawl across pages and sections, matching against the query.
   * @param {string} query - Search text.
   * @param {number} [maxPages=800] - Max pages to scan.
   * @param {number} [limit=25] - Max matches to return.
   * @returns {Promise<Array<{ title: string, url: string }>>} Matched results.
   */
  async function searchGuideNormalizedNoCache(query, maxPages = 800, limit = 25) {
    const norm = normalizeId(query);
    const seen = new Set();
    const matches = [];

    const queue = [
      BASE,
      canonicalEnUsHtml(`${BASE}tfg_ores.html`),
      canonicalEnUsHtml(`${BASE}tfg_ores/earth_ore_index.html`),
      canonicalEnUsHtml(`${BASE}tfg_ores/earth_vein_index.html`),
    ];
    const visited = new Set(queue);
    let scanned = 0;

    while (queue.length && scanned < maxPages && matches.length < limit) {
      const current = queue.shift();
      let html;
      try {
        html = await fetchHtml(current);
      } catch {
        continue;
      };
      scanned++;

      const rel = current.startsWith(BASE) ? current.slice(BASE.length) : current;
      if (filenameAndFragmentMatch(rel, norm) && !seen.has(current)) {
        matches.push({ title: current, url: current });
        seen.add(current);
      };

      const sections = await parseSectionsNoCache(current);
      for (const s of sections) {
        const idMatch = normalizedHasToken(s.id || '', norm);
        const titleMatch = normalizedHasToken(s.title || '', norm);
        const relS = s.url.startsWith(BASE) ? s.url.slice(BASE.length) : s.url;
        const pathMatchStrict = filenameAndFragmentMatch(relS, norm);
        if ((idMatch || titleMatch || pathMatchStrict) && !isBlacklistedFragment(s.id) && !isBlacklistedFragment(s.url)) {
          if (!seen.has(s.url)) {
            matches.push({ title: s.title, url: s.url });
            seen.add(s.url);
            if (matches.length >= limit) break;
          };
        };
      };

      for (const link of collectLinksEnUs(html, current)) {
        if (!visited.has(link)) {
          visited.add(link);
          queue.push(link);
        };
      };
    };

    return matches;
  };

  /**
   * Search wrapper with a lower page scan cap.
   * @param {string} query - Search text.
   * @param {number} [maxPages=80] - Max pages to scan.
   * @returns {Promise<Array<{ title: string, url: string }>>} Matched results.
   */
  async function searchGuideDeep(query, maxPages = 80) {
    return await searchGuideNormalizedNoCache(query, maxPages, 25);
  };

  module.exports = {
    BASE,
    buildUrlFromPath,
    fetchGuideEmbed,
    searchGuideDeep,
    searchGuideNormalizedNoCache,
    searchGuideViaIndex,
    searchGuideFast,
  };
  // Thank you for listening to my TED talk.