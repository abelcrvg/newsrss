import * as cheerio from "cheerio";
import type { NewsItem } from "../types";
import { absoluteUrl, cleanText, fetchHtml } from "../http";
import type { SourceConfig } from "../sources";

const BAD_IMAGE = /(escudo|badge|crest|club-logo|team-logo|avatar|author|icon|logo|sprite|tracking|pixel|placeholder|banner|ads?)/i;
const NON_ARTICLE_PATH = /\/(busca|buscar|search|tag|tags|categoria|categorias|category|autor|autores|author|authors|coluna|colunas|column|columns|video|videos|podcast|podcasts|foto|fotos|galeria|galerias|newsletter|about|sobre|contato|contact|login|entrar|cadastro|register|privacy|privacidade|termos)(\/|$)/i;
const GENERIC_TITLE = /^(home|início|inicio|notícias|noticias|últimas notícias|ultimas noticias|vídeos|videos|fotos|podcasts|esportes|entretenimento|tecnologia|busca|buscar|pesquisa|mais lidas|mais lidos|ao vivo)$/i;

export async function crawlGenericHomepage(source: SourceConfig): Promise<NewsItem[]> {
  const html = await fetchHtml(source.url);
  const $ = cheerio.load(html);
  const found = new Map<string, NewsItem>();

  $("article a[href], a[href]").each((_, el) => {
    if (found.size >= 100) return;
    const link = $(el);
    const url = absoluteUrl(link.attr("href") || "", source.url);
    if (!url || !sameHost(url, source.url) || !looksLikeArticle(url)) return;

    const root = link.closest("article, [class*='feed-post'], [class*='feed-item'], [class*='card'], [class*='story'], [class*='article'], [class*='headline'], [class*='tile']");
    const card = root.length ? root : link;
    const title = cleanText(card.find("h1,h2,h3,h4").first().text()) || cleanText(link.text());
    if (!isUsableTitle(title)) return;

    const subtitle = cleanText(card.find("p").first().text());
    const image = pickImage($, card, source.url);
    const item: NewsItem = {
      id: `${source.id}-${Buffer.from(url).toString("base64url")}`,
      source: source.name,
      category: source.category,
      title,
      subtitle: subtitle && subtitle !== title ? subtitle : undefined,
      url,
      image
    };

    const existing = found.get(url);
    if (!existing || (!existing.image && image)) found.set(url, item);
  });

  return Array.from(found.values());
}

function sameHost(a: string, b: string) {
  try {
    const left = new URL(a).hostname.replace(/^www\./, "");
    const right = new URL(b).hostname.replace(/^www\./, "");
    return left === right;
  } catch {
    return false;
  }
}

function looksLikeArticle(url: string) {
  try {
    const parsed = new URL(url);
    const path = parsed.pathname.toLowerCase().replace(/\/$/, "");
    if (!path || path === "/" || path.length < 8 || NON_ARTICLE_PATH.test(path)) return false;

    const segments = path.split("/").filter(Boolean);
    if (segments.length < 2) return false;

    const last = segments.at(-1) || "";
    const hasArticleMarker = /(^|\/)(noticia|news|article|materia|story|stories)(\/|$)/i.test(path);
    const hasSlug = /[a-z]/i.test(last) && last.length >= 12 && last.includes("-");
    return hasArticleMarker || hasSlug;
  } catch {
    return false;
  }
}

function isUsableTitle(title: string) {
  if (title.length < 20 || title.length > 240 || GENERIC_TITLE.test(title)) return false;
  return /[a-záàâãéêíóôõúç]/i.test(title);
}

function pickImage($: cheerio.CheerioAPI, root: cheerio.Cheerio<any>, base: string) {
  const candidates: string[] = [];
  root.find("img, source").each((_, el) => {
    const node = $(el);
    const value = node.attr("src") || node.attr("data-src") || node.attr("data-original") || pickSrcset(node.attr("srcset"));
    if (!value || BAD_IMAGE.test(value)) return;
    const absolute = absoluteUrl(value, base);
    if (absolute && !candidates.includes(absolute)) candidates.push(absolute);
  });
  return candidates[0];
}

function pickSrcset(value?: string) {
  if (!value) return undefined;
  return value
    .split(",")
    .map(part => part.trim().split(/\s+/)[0])
    .filter(Boolean)
    .at(-1);
}
