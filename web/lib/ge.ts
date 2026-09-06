import * as cheerio from "cheerio";
import type { NewsItem } from "./types";

const HOME = "https://ge.globo.com/";
const UA = "NewsRSS/2.0 (+https://github.com/abelcrvg/newsrss)";

function absolute(url: string, base = HOME) {
  try { return new URL(url, base).toString(); } catch { return ""; }
}

function clean(value?: string) {
  return value?.replace(/\s+/g, " ").trim() || "";
}

function isArticleUrl(url: string) {
  try {
    const u = new URL(url);
    if (!u.hostname.endsWith("globo.com")) return false;
    return /\/futebol\/.+\/noticia\//i.test(u.pathname) || /\/esportes\/.+\/noticia\//i.test(u.pathname) || /\/noticia\//i.test(u.pathname);
  } catch { return false; }
}

function pickImage($: cheerio.CheerioAPI, root: cheerio.Cheerio<any>) {
  const bad = /(escudo|badge|crest|club-logo|team-logo|avatar|author|icon|logo|sprite|tracking|pixel|placeholder)/i;
  const candidates: string[] = [];
  root.find("img, source").each((_, el) => {
    const node = $(el);
    const src = node.attr("src") || node.attr("data-src") || node.attr("data-original") || node.attr("srcset")?.split(",").pop()?.trim().split(" ")[0];
    if (src && !bad.test(src)) candidates.push(absolute(src));
  });
  return candidates.find(Boolean);
}

function jsonLdDates($: cheerio.CheerioAPI) {
  let published: string | undefined;
  let updated: string | undefined;
  $("script[type='application/ld+json']").each((_, el) => {
    if (published && updated) return;
    try {
      const raw = JSON.parse($(el).text());
      const nodes = Array.isArray(raw) ? raw : [raw];
      for (const node of nodes) {
        if (!node || typeof node !== "object") continue;
        published ||= node.datePublished;
        updated ||= node.dateModified;
        if (node["@graph"] && Array.isArray(node["@graph"])) {
          for (const item of node["@graph"]) {
            published ||= item?.datePublished;
            updated ||= item?.dateModified;
          }
        }
      }
    } catch { /* malformed JSON-LD */ }
  });
  return { published, updated };
}

function extractDate($: cheerio.CheerioAPI, published = true) {
  const selectors = published
    ? ["meta[property='article:published_time']", "meta[name='date']", "[itemprop='datePublished']", "time[datetime]"]
    : ["meta[property='article:modified_time']", "[itemprop='dateModified']"];
  for (const selector of selectors) {
    const node = $(selector).first();
    if (!node.length) continue;
    const value = node.attr("content") || node.attr("datetime") || node.text();
    if (value && !Number.isNaN(Date.parse(value))) return new Date(value).toISOString();
  }
  return undefined;
}

export async function crawlGE(): Promise<NewsItem[]> {
  const response = await fetch(HOME, { headers: { "user-agent": UA, accept: "text/html,application/xhtml+xml" }, next: { revalidate: 120 } });
  if (!response.ok) throw new Error(`GE respondeu HTTP ${response.status}`);
  const html = await response.text();
  const $ = cheerio.load(html);
  const found = new Map<string, NewsItem>();

  $("a[href]").each((_, el) => {
    const link = $(el);
    const url = absolute(link.attr("href") || "");
    if (!isArticleUrl(url)) return;
    const card = link.closest("article, [class*='feed-post'], [class*='feed-item'], [class*='card'], [class*='story']");
    const root = card.length ? card : link;
    const title = clean(root.find("h1,h2,h3,h4").first().text()) || clean(link.text());
    if (title.length < 20 || title.length > 220) return;
    const subtitle = clean(root.find("p").first().text());
    const image = pickImage($, root);
    const existing = found.get(url);
    if (!existing || (!existing.image && image)) {
      found.set(url, { id: `ge-${Buffer.from(url).toString("base64url")}`, source: "GE", category: "football", title, subtitle: subtitle && subtitle !== title ? subtitle : undefined, url, image });
    }
  });

  const items = Array.from(found.values()).slice(0, 80);
  const enriched = await Promise.all(items.map(async item => {
    try {
      const res = await fetch(item.url, { headers: { "user-agent": UA, accept: "text/html,application/xhtml+xml" }, next: { revalidate: 120 } });
      if (!res.ok) return item;
      const articleHtml = await res.text();
      const article$ = cheerio.load(articleHtml);
      const ld = jsonLdDates(article$);
      const publishedAt = ld.published || extractDate(article$, true);
      const updatedAt = ld.updated || extractDate(article$, false);
      const title = clean(article$("meta[property='og:title']").attr("content")) || item.title;
      const subtitle = clean(article$("meta[property='og:description']").attr("content")) || item.subtitle;
      const image = clean(article$("meta[property='og:image']").attr("content")) || item.image;
      return { ...item, title, subtitle, image: image ? absolute(image, item.url) : undefined, publishedAt, updatedAt };
    } catch {
      return item;
    }
  }));

  return enriched.sort((a, b) => (b.publishedAt ? Date.parse(b.publishedAt) : 0) - (a.publishedAt ? Date.parse(a.publishedAt) : 0));
}
