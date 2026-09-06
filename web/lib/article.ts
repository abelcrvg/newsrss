import * as cheerio from "cheerio";
import { cleanText, fetchHtml } from "./http";

export type ArticleContent = {
  title: string;
  subtitle?: string;
  image?: string;
  author?: string;
  publishedAt?: string;
  updatedAt?: string;
  paragraphs: string[];
};

const NOISE = "script,style,noscript,nav,footer,header,form,aside,[aria-hidden='true'],[class*='ad-'],[class*='advert'],[id*='ad-'],[class*='comment'],[class*='related'],[class*='newsletter'],[class*='share']";

export async function extractArticle(url: string): Promise<ArticleContent> {
  const parsed = new URL(url);
  if (!["http:", "https:"].includes(parsed.protocol)) throw new Error("URL inválida");

  const html = await fetchHtml(url);
  const $ = cheerio.load(html);
  $(NOISE).remove();

  const title = cleanText($("meta[property='og:title']").attr("content")) || cleanText($("h1").first().text()) || cleanText($("title").text());
  const subtitle = cleanText($("meta[property='og:description']").attr("content")) || undefined;
  const image = cleanText($("meta[property='og:image']").attr("content")) || undefined;
  const author = cleanText($("meta[name='author']").attr("content")) || cleanText($("[rel='author'],[itemprop='author']").first().text()) || undefined;
  const publishedAt = dateFrom($, "datePublished", "article:published_time");
  const updatedAt = dateFrom($, "dateModified", "article:modified_time");

  const candidates = [
    "article",
    "[itemprop='articleBody']",
    "[class*='article-body']",
    "[class*='articleBody']",
    "[class*='article-content']",
    "[class*='post-content']",
    "main"
  ];

  let best = "";
  for (const selector of candidates) {
    const node = $(selector).first();
    if (!node.length) continue;
    const text = cleanText(node.text());
    if (text.length > best.length) best = text;
  }

  if (best.length < 120) throw new Error("Não foi possível extrair o conteúdo da matéria");

  const root = candidates.map(selector => $(selector).first()).find(node => node.length && cleanText(node.text()).length === best.length);
  const paragraphs = root && root.length
    ? root.find("p, h2, h3, blockquote").map((_, el) => cleanText($(el).text())).get().filter((text, index, all) => text.length >= 20 && text !== title && text !== subtitle && all.indexOf(text) === index)
    : best.split(/\n+/).map(cleanText).filter(Boolean);

  const finalParagraphs = paragraphs.length ? paragraphs : [best];
  return { title, subtitle: subtitle && subtitle !== title ? subtitle : undefined, image, author, publishedAt, updatedAt, paragraphs: finalParagraphs };
}

function dateFrom($: cheerio.CheerioAPI, jsonKey: "datePublished" | "dateModified", metaName: string) {
  let value: string | undefined;
  $("script[type='application/ld+json']").each((_, el) => {
    if (value) return;
    try {
      const raw = JSON.parse($(el).text());
      const nodes = Array.isArray(raw) ? raw : [raw];
      for (const node of nodes) {
        const graph = Array.isArray(node?.["@graph"]) ? node["@graph"] : [];
        for (const item of [node, ...graph]) {
          if (item?.[jsonKey]) { value = String(item[jsonKey]); break; }
        }
        if (value) break;
      }
    } catch {}
  });
  value ||= $("meta[property='" + metaName + "'], meta[name='" + metaName + "']").first().attr("content");
  const parsed = value ? Date.parse(value) : NaN;
  return Number.isNaN(parsed) ? undefined : new Date(parsed).toISOString();
}
