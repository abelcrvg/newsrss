import * as cheerio from "cheerio";
import type { NewsItem } from "../types";
import { absoluteUrl, cleanText, fetchHtml } from "../http";

const HOME = "https://g1.globo.com/";
const CONCURRENCY = 8;

export async function crawlG1(): Promise<NewsItem[]> {
  const html = await fetchHtml(HOME);
  const $ = cheerio.load(html);
  const found = new Map<string, NewsItem>();

  const selectors = [
    ".feed-post-link",
    "a.feed-post-link",
    "[class*='feed-post'] a[href]",
    "article a[href]"
  ];

  for (const selector of selectors) {
    $(selector).each((_, el) => {
      const link = $(el);
      const url = absoluteUrl(link.attr("href") || "", HOME);
      if (!isG1Article(url)) return;

      const card = link.closest("article, [class*='feed-post'], [class*='feed-item'], [class*='card'], [class*='story']");
      const root = card.length ? card : link;
      const title = cleanText(
        root.find(".feed-post-body-title, .feed-post-link, h1, h2, h3, h4").first().text()
      ) || cleanText(link.text());
      if (!isUsableTitle(title)) return;

      const subtitle = cleanText(
        root.find(".feed-post-body-resumo, .feed-post-body, p").first().text()
      );
      const image = pickImage($, root);
      const existing = found.get(url);
      if (!existing || (!existing.image && image)) {
        found.set(url, {
          id: `g1-${Buffer.from(url).toString("base64url")}`,
          source: "G1",
          category: "news",
          title,
          subtitle: subtitle && subtitle !== title ? subtitle : undefined,
          url,
          image
        });
      }
    });
  }

  const candidates = Array.from(found.values());
  const enriched: NewsItem[] = [];
  for (let index = 0; index < candidates.length; index += CONCURRENCY) {
    const batch = candidates.slice(index, index + CONCURRENCY);
    const results = await Promise.all(batch.map(enrich));
    enriched.push(...results);
  }

  return enriched.sort((a, b) => dateValue(b.publishedAt) - dateValue(a.publishedAt));
}

async function enrich(item: NewsItem): Promise<NewsItem> {
  try {
    const html = await fetchHtml(item.url);
    const $ = cheerio.load(html);
    const publishedAt = jsonLdDate($, "datePublished") || metaDate($, "article:published_time") || metaDate($, "date") || timeDate($);
    const updatedAt = jsonLdDate($, "dateModified") || metaDate($, "article:modified_time");
    const title = cleanText($("meta[property='og:title']").attr("content")) || item.title;
    const subtitle = cleanText($("meta[property='og:description']").attr("content")) || item.subtitle;
    const image = cleanText($("meta[property='og:image']").attr("content")) || twitterImage($) || item.image;
    const author = cleanText($("meta[name='author']").attr("content")) || cleanText($("[rel='author'],[itemprop='author']").first().text()) || undefined;
    return {
      ...item,
      title,
      subtitle: subtitle && subtitle !== title ? subtitle : undefined,
      image: image ? absoluteUrl(image, item.url) : undefined,
      author,
      publishedAt,
      updatedAt
    };
  } catch {
    return item;
  }
}

function isG1Article(url: string) {
  try {
    const parsed = new URL(url);
    return parsed.hostname === "g1.globo.com" && /\/noticia\//i.test(parsed.pathname);
  } catch {
    return false;
  }
}

function isUsableTitle(title: string) {
  if (title.length < 20 || title.length > 240) return false;
  return !/^(primeira página|minas gerais|moda e beleza|fotos|vídeos|podcasts|g1 em ½?minuto)$/i.test(title);
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
        for (const item of list) {
          if (item?.[key]) value = String(item[key]);
          if (value) break;
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

function twitterImage($: cheerio.CheerioAPI) {
  return cleanText($("meta[name='twitter:image']").attr("content")) || undefined;
}

function pickImage($: cheerio.CheerioAPI, root: cheerio.Cheerio<any>) {
  const candidates: string[] = [];
  root.find("img, source").each((_, el) => {
    const node = $(el);
    const value = node.attr("src") || node.attr("data-src") || node.attr("data-original") || node.attr("data-lazy-src") || srcsetValue(node.attr("srcset"));
    if (value && !/(logo|avatar|author|icon|sprite|pixel|tracking|placeholder|banner)/i.test(value)) candidates.push(absoluteUrl(value, HOME));
  });
  return candidates.find(Boolean);
}

function srcsetValue(value?: string) {
  if (!value) return "";
  return value.split(",").map(part => part.trim().split(/\s+/)[0]).filter(Boolean).pop() || "";
}

function dateValue(value?: string) {
  const n = value ? Date.parse(value) : NaN;
  return Number.isNaN(n) ? 0 : n;
}
