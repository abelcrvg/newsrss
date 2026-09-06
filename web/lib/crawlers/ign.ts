import * as cheerio from "cheerio";
import type { NewsItem } from "../types";
import { absoluteUrl, cleanText, fetchHtml } from "../http";

const HOME = "https://br.ign.com/";
const HOST = "br.ign.com";
const MAX_ITEMS = 120;
const CONCURRENCY = 8;
const BAD_IMAGE = /(logo|avatar|author|icon|sprite|pixel|tracking|placeholder|banner)/i;

export async function crawlIGN(): Promise<NewsItem[]> {
  const html = await fetchHtml(HOME);
  const $ = cheerio.load(html);
  const found = new Map<string, NewsItem>();

  $("article a[href], a[href]").each((_, el) => {
    if (found.size >= MAX_ITEMS) return;
    const link = $(el);
    const url = absoluteUrl(link.attr("href") || "", HOME);
    if (!isArticle(url)) return;
    const root = link.closest("article, [class*='item'], [class*='card'], [class*='article'], [class*='content']");
    const card = root.length ? root : link;
    const title = cleanText(card.find("h1,h2,h3,h4").first().text()) || cleanText(link.text());
    if (!isUsableTitle(title)) return;
    const subtitle = cleanText(card.find("p").first().text());
    const image = pickImage($, card);
    const existing = found.get(url);
    if (!existing || (!existing.image && image)) {
      found.set(url, { id: `ign-brasil-${Buffer.from(url).toString("base64url")}`, source: "IGN Brasil", category: "games", title, subtitle: subtitle && subtitle !== title ? subtitle : undefined, url, image });
    }
  });

  const candidates = Array.from(found.values());
  const enriched: NewsItem[] = [];
  for (let i = 0; i < candidates.length; i += CONCURRENCY) {
    enriched.push(...await Promise.all(candidates.slice(i, i + CONCURRENCY).map(enrich)));
  }
  return enriched.sort((a, b) => dateValue(b.publishedAt) - dateValue(a.publishedAt));
}

function isArticle(url: string) {
  try {
    const u = new URL(url);
    const path = u.pathname.toLowerCase();
    if (u.hostname !== HOST || path === "/" || path.length < 8) return false;
    return !/\/(tag|tags|autor|authors|busca|search|videos|video|podcasts|listas|lista|especial)(\/|$)/i.test(path) && path.split("/").filter(Boolean).length >= 2;
  } catch { return false; }
}

function isUsableTitle(title: string) {
  return title.length >= 20 && title.length <= 240 && !/^(home|início|notícias|vídeos|reviews|guias)$/i.test(title);
}

async function enrich(item: NewsItem): Promise<NewsItem> {
  try {
    const html = await fetchHtml(item.url);
    const $ = cheerio.load(html);
    const publishedAt = jsonLdDate($, "datePublished") || metaDate($, "article:published_time") || timeDate($);
    const updatedAt = jsonLdDate($, "dateModified") || metaDate($, "article:modified_time");
    const title = cleanText($("meta[property='og:title']").attr("content")) || item.title;
    const subtitle = cleanText($("meta[property='og:description']").attr("content")) || item.subtitle;
    const image = cleanText($("meta[property='og:image']").attr("content")) || cleanText($("meta[name='twitter:image']").attr("content")) || item.image;
    const author = cleanText($("meta[name='author']").attr("content")) || cleanText($("[rel='author'],[itemprop='author']").first().text()) || undefined;
    return { ...item, title, subtitle: subtitle && subtitle !== title ? subtitle : undefined, image: image ? absoluteUrl(image, item.url) : undefined, author, publishedAt, updatedAt };
  } catch { return item; }
}

function jsonLdDate($: cheerio.CheerioAPI, key: "datePublished" | "dateModified") {
  let value: string | undefined;
  $("script[type='application/ld+json']").each((_, el) => {
    if (value) return;
    try {
      const raw = JSON.parse($(el).text());
      const nodes = Array.isArray(raw) ? raw : [raw];
      for (const node of nodes) {
        const graph = Array.isArray(node?.["@graph"]) ? node["@graph"] : [];
        for (const item of [node, ...graph]) {
          if (item?.[key]) { value = String(item[key]); break; }
        }
        if (value) break;
      }
    } catch {}
  });
  return value && !Number.isNaN(Date.parse(value)) ? new Date(value).toISOString() : undefined;
}

function metaDate($: cheerio.CheerioAPI, name: string) {
  const value = $("meta[property='" + name + "'], meta[name='" + name + "']").first().attr("content");
  return value && !Number.isNaN(Date.parse(value)) ? new Date(value).toISOString() : undefined;
}

function timeDate($: cheerio.CheerioAPI) {
  const value = $("time[datetime]").first().attr("datetime");
  return value && !Number.isNaN(Date.parse(value)) ? new Date(value).toISOString() : undefined;
}

function pickImage($: cheerio.CheerioAPI, root: cheerio.Cheerio<any>) {
  const values: string[] = [];
  root.find("img,source").each((_, el) => {
    const node = $(el);
    const value = node.attr("src") || node.attr("data-src") || node.attr("data-original") || node.attr("srcset")?.split(",").pop()?.trim().split(/\s+/)[0];
    if (value && !BAD_IMAGE.test(value)) values.push(absoluteUrl(value, HOME));
  });
  return values.find(Boolean);
}

function dateValue(value?: string) {
  const n = value ? Date.parse(value) : NaN;
  return Number.isNaN(n) ? 0 : n;
}
