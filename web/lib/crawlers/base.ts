import * as cheerio from "cheerio";
import type { NewsItem } from "../types";
import { absoluteUrl, cleanText, fetchHtml } from "../http";
import type { SourceConfig } from "../sources";

const BAD_IMAGE = /(escudo|badge|crest|club-logo|team-logo|avatar|author|icon|logo|sprite|tracking|pixel|placeholder)/i;

export async function crawlGenericHomepage(source: SourceConfig): Promise<NewsItem[]> {
  const html = await fetchHtml(source.url);
  const $ = cheerio.load(html);
  const found = new Map<string, NewsItem>();

  $("a[href]").each((_, el) => {
    const link = $(el);
    const url = absoluteUrl(link.attr("href") || "", source.url);
    if (!url || !sameHost(url, source.url) || !looksLikeArticle(url)) return;
    const card = link.closest("article, [class*='feed-post'], [class*='feed-item'], [class*='card'], [class*='story'], [class*='article']");
    const root = card.length ? card : link;
    const title = cleanText(root.find("h1,h2,h3,h4").first().text()) || cleanText(link.text());
    if (title.length < 20 || title.length > 240) return;
    const image = pickImage($, root, source.url);
    found.set(url, { id: `${source.id}-${Buffer.from(url).toString("base64url")}`, source: source.name, category: source.category, title, url, image });
  });

  return Array.from(found.values()).slice(0, 100);
}

function sameHost(a: string, b: string) {
  try { return new URL(a).hostname === new URL(b).hostname; } catch { return false; }
}

function looksLikeArticle(url: string) {
  try {
    const path = new URL(url).pathname.toLowerCase();
    if (!path || path === "/") return false;
    return /\/noticia\/|\/news\/|\/article\/|\/materia\/|\/story\//i.test(path) || path.split("/").filter(Boolean).length >= 2;
  } catch { return false; }
}

function pickImage($: cheerio.CheerioAPI, root: cheerio.Cheerio<any>, base: string) {
  const candidates: string[] = [];
  root.find("img, source").each((_, el) => {
    const node = $(el);
    const value = node.attr("src") || node.attr("data-src") || node.attr("data-original") || node.attr("srcset")?.split(",").pop()?.trim().split(" ")[0];
    if (value && !BAD_IMAGE.test(value)) candidates.push(absoluteUrl(value, base));
  });
  return candidates.find(Boolean);
}
