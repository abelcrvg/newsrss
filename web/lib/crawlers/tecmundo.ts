import * as cheerio from "cheerio";
import type { NewsItem } from "../types";
import { absoluteUrl, cleanText, fetchHtml } from "../http";

const HOME = "https://www.tecmundo.com.br/";
const HOST = "www.tecmundo.com.br";
const CONCURRENCY = 8;
const BAD_IMAGE = /(logo|avatar|author|icon|sprite|pixel|tracking|placeholder|banner)/i;

export async function crawlTecmundo(): Promise<NewsItem[]> {
  return crawlSite(HOME, "tecmundo", "TecMundo", "technology", url => isTecmundoArticle(url));
}

export async function crawlVoxel(): Promise<NewsItem[]> {
  const home = "https://www.tecmundo.com.br/voxel/";
  return crawlSite(home, "voxel", "Voxel", "games", url => isVoxelArticle(url));
}

async function crawlSite(home: string, sourceId: string, source: string, category: NewsItem["category"], articleCheck: (url: string) => boolean): Promise<NewsItem[]> {
  const html = await fetchHtml(home);
  const $ = cheerio.load(html);
  const found = new Map<string, NewsItem>();
  for (const selector of ["article a[href]", "a[data-testid*='article'][href]", "a[href]"]) {
    $(selector).each((_, el) => {
      const link = $(el);
      const url = absoluteUrl(link.attr("href") || "", home);
      if (!articleCheck(url)) return;
      const card = link.closest("article, [class*='feed'], [class*='card'], [class*='post'], [class*='article'], [class*='item']");
      const root = card.length ? card : link;
      const title = cleanText(root.find("h1,h2,h3,h4").first().text()) || cleanText(link.text());
      if (!isUsableTitle(title)) return;
      const subtitle = cleanText(root.find("p").first().text());
      const image = pickImage($, root, home);
      const existing = found.get(url);
      if (!existing || (!existing.image && image)) found.set(url, { id: `${sourceId}-${Buffer.from(url).toString("base64url")}`, source, category, title, subtitle: subtitle && subtitle !== title ? subtitle : undefined, url, image });
    });
  }
  const candidates = Array.from(found.values());
  const enriched: NewsItem[] = [];
  for (let i = 0; i < candidates.length; i += CONCURRENCY) enriched.push(...await Promise.all(candidates.slice(i, i + CONCURRENCY).map(enrich)));
  return enriched.sort((a, b) => dateValue(b.publishedAt) - dateValue(a.publishedAt));
}

function isTecmundoArticle(url: string) {
  try { const u = new URL(url); const path = u.pathname.toLowerCase(); return u.hostname === HOST && path !== "/" && !path.startsWith("/voxel/") && path.split("/").filter(Boolean).length >= 2 && !isNonArticlePath(path); } catch { return false; }
}
function isVoxelArticle(url: string) {
  try { const u = new URL(url); const path = u.pathname.toLowerCase(); return u.hostname === HOST && path.startsWith("/voxel/") && path.split("/").filter(Boolean).length >= 2 && !isNonArticlePath(path); } catch { return false; }
}
function isNonArticlePath(path: string) { return /\/(autor|autoridade|tags|tag|busca|search|videos|galeria|lista|especial|colunistas)(\/|$)/i.test(path) || /\.(jpg|jpeg|png|gif|webp|svg)$/i.test(path); }
function isUsableTitle(title: string) { return title.length >= 20 && title.length <= 240 && !/^(home|início|mais lidas|últimas notícias|tecnologia|games)$/i.test(title); }

async function enrich(item: NewsItem): Promise<NewsItem> {
  try {
    const html = await fetchHtml(item.url); const $ = cheerio.load(html);
    const publishedAt = jsonLdDate($, "datePublished") || metaDate($, "article:published_time") || timeDate($);
    const updatedAt = jsonLdDate($, "dateModified") || metaDate($, "article:modified_time");
    const title = cleanText($("meta[property='og:title']").attr("content")) || item.title;
    const subtitle = cleanText($("meta[property='og:description']").attr("content")) || item.subtitle;
    const image = cleanText($("meta[property='og:image']").attr("content")) || cleanText($("meta[name='twitter:image']").attr("content")) || item.image;
    const author = cleanText($("meta[name='author']").attr("content")) || cleanText($("[rel='author'],[itemprop='author']").first().text()) || undefined;
    return { ...item, title, subtitle: subtitle && subtitle !== title ? subtitle : undefined, image: image ? absoluteUrl(image, item.url) : undefined, author, publishedAt, updatedAt };
  } catch { return item; }
}
function jsonLdDate($: cheerio.CheerioAPI, key: "datePublished" | "dateModified") { let value: string | undefined; $("script[type='application/ld+json']").each((_, el) => { if (value) return; try { const raw = JSON.parse($(el).text()); const nodes = Array.isArray(raw) ? raw : [raw]; for (const node of nodes) { const graph = Array.isArray(node?.["@graph"]) ? node["@graph"] : []; for (const item of [node, ...graph]) { if (item?.[key]) { value = String(item[key]); break; } } if (value) break; } } catch {} }); return value && !Number.isNaN(Date.parse(value)) ? new Date(value).toISOString() : undefined; }
function metaDate($: cheerio.CheerioAPI, name: string) { const value = $("meta[property='" + name + "'], meta[name='" + name + "']").first().attr("content"); return value && !Number.isNaN(Date.parse(value)) ? new Date(value).toISOString() : undefined; }
function timeDate($: cheerio.CheerioAPI) { const value = $("time[datetime]").first().attr("datetime"); return value && !Number.isNaN(Date.parse(value)) ? new Date(value).toISOString() : undefined; }
function pickImage($: cheerio.CheerioAPI, root: cheerio.Cheerio<any>, base: string) { const candidates: string[] = []; root.find("img, source").each((_, el) => { const node = $(el); const value = node.attr("src") || node.attr("data-src") || node.attr("data-original") || node.attr("data-lazy-src") || srcsetValue(node.attr("srcset")); if (value && !BAD_IMAGE.test(value)) candidates.push(absoluteUrl(value, base)); }); return candidates.find(Boolean); }
function srcsetValue(value?: string) { if (!value) return ""; return value.split(",").map(part => part.trim().split(/\s+/)[0]).filter(Boolean).pop() || ""; }
function dateValue(value?: string) { const n = value ? Date.parse(value) : NaN; return Number.isNaN(n) ? 0 : n; }
