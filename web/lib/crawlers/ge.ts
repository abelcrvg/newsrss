import * as cheerio from "cheerio";
import type { NewsItem } from "../types";
import { absoluteUrl, cleanText, fetchHtml } from "../http";

const HOME = "https://ge.globo.com/";

export async function crawlGE(): Promise<NewsItem[]> {
  const html = await fetchHtml(HOME);
  const $ = cheerio.load(html);
  const found = new Map<string, NewsItem>();

  $("a[href]").each((_, el) => {
    const link = $(el);
    const url = absoluteUrl(link.attr("href") || "", HOME);
    if (!isArticle(url)) return;
    const card = link.closest("article, [class*='feed-post'], [class*='feed-item'], [class*='card'], [class*='story']");
    const root = card.length ? card : link;
    const title = cleanText(root.find("h1,h2,h3,h4").first().text()) || cleanText(link.text());
    if (title.length < 15 || title.length > 240) return;
    const subtitle = cleanText(root.find("p").first().text());
    const image = pickImage($, root);
    found.set(url, { id: `ge-${Buffer.from(url).toString("base64url")}`, source: "GE", category: "football", title, subtitle: subtitle && subtitle !== title ? subtitle : undefined, url, image });
  });

  const items = Array.from(found.values()).slice(0, 100);
  const enriched = await Promise.all(items.map(enrich));
  return enriched.sort((a, b) => dateValue(b.publishedAt) - dateValue(a.publishedAt));
}

async function enrich(item: NewsItem): Promise<NewsItem> {
  try {
    const html = await fetchHtml(item.url);
    const $ = cheerio.load(html);
    const publishedAt = jsonLdDate($, "datePublished") || metaDate($, "article:published_time") || timeDate($);
    const updatedAt = jsonLdDate($, "dateModified") || metaDate($, "article:modified_time");
    const title = cleanText($("meta[property='og:title']").attr("content")) || item.title;
    const subtitle = cleanText($("meta[property='og:description']").attr("content")) || item.subtitle;
    const image = cleanText($("meta[property='og:image']").attr("content")) || item.image;
    const author = cleanText($("meta[name='author']").attr("content")) || cleanText($("[rel='author'],[itemprop='author']").first().text()) || undefined;
    return { ...item, title, subtitle: subtitle && subtitle !== title ? subtitle : undefined, image: image ? absoluteUrl(image, item.url) : undefined, author, publishedAt, updatedAt };
  } catch { return item; }
}

function isArticle(url: string) {
  try { return new URL(url).hostname.endsWith("globo.com") && /\/futebol\/|\/esportes\/|\/noticia\//i.test(new URL(url).pathname); } catch { return false; }
}
function jsonLdDate($: cheerio.CheerioAPI, key: "datePublished" | "dateModified") {
  let value: string | undefined;
  $("script[type='application/ld+json']").each((_, el) => {
    if (value) return;
    try {
      const raw = JSON.parse($(el).text());
      const nodes = Array.isArray(raw) ? raw : [raw];
      for (const node of nodes) {
        const list = node?.["@graph"] && Array.isArray(node["@graph"]) ? [node, ...node["@graph"]] : [node];
        for (const item of list) value ||= item?.[key];
      }
    } catch {}
  });
  return value;
}
function metaDate($: cheerio.CheerioAPI, property: string) {
  const value = $("meta[property='" + property + "']").attr("content");
  return value && !Number.isNaN(Date.parse(value)) ? new Date(value).toISOString() : undefined;
}
function timeDate($: cheerio.CheerioAPI) {
  const value = $("time[datetime]").first().attr("datetime");
  return value && !Number.isNaN(Date.parse(value)) ? new Date(value).toISOString() : undefined;
}
function pickImage($: cheerio.CheerioAPI, root: cheerio.Cheerio<any>) {
  const candidates: string[] = [];
  root.find("img, source").each((_, el) => {
    const node = $(el);
    const value = node.attr("src") || node.attr("data-src") || node.attr("srcset")?.split(",").pop()?.trim().split(" ")[0];
    if (value && !/(escudo|badge|crest|club-logo|team-logo|avatar|logo|sprite|pixel)/i.test(value)) candidates.push(absoluteUrl(value, HOME));
  });
  return candidates.find(Boolean);
}
function dateValue(value?: string) { const n = value ? Date.parse(value) : NaN; return Number.isNaN(n) ? 0 : n; }
