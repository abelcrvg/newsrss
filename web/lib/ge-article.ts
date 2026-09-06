import * as cheerio from "cheerio";

export type GEArticle = {
  title: string;
  subtitle?: string;
  author?: string;
  publishedAt?: string;
  updatedAt?: string;
  image?: string;
  paragraphs: string[];
};

function clean(value?: string) { return value?.replace(/\s+/g, " ").trim() || ""; }

function jsonLd($: cheerio.CheerioAPI) {
  const result: { publishedAt?: string; updatedAt?: string; author?: string } = {};
  $("script[type='application/ld+json']").each((_, el) => {
    if (result.publishedAt && result.updatedAt && result.author) return;
    try {
      const raw = JSON.parse($(el).text());
      const nodes = Array.isArray(raw) ? raw : [raw];
      for (const node of nodes) {
        const list = node?.["@graph"] && Array.isArray(node["@graph"]) ? [node, ...node["@graph"]] : [node];
        for (const item of list) {
          if (!item || typeof item !== "object") continue;
          result.publishedAt ||= item.datePublished;
          result.updatedAt ||= item.dateModified;
          if (!result.author) result.author = typeof item.author === "string" ? item.author : item.author?.name;
        }
      }
    } catch { /* ignore malformed JSON-LD */ }
  });
  return result;
}

function imageUrl($: cheerio.CheerioAPI, pageUrl: string) {
  const raw = $("meta[property='og:image']").attr("content") || $("meta[name='twitter:image']").attr("content");
  if (!raw) return undefined;
  try { return new URL(raw, pageUrl).toString(); } catch { return raw; }
}

export async function crawlGEArticle(url: string): Promise<GEArticle> {
  const response = await fetch(url, { headers: { "user-agent": "NewsRSS/2.0", accept: "text/html,application/xhtml+xml" }, next: { revalidate: 120 } });
  if (!response.ok) throw new Error(`GE respondeu HTTP ${response.status}`);
  const html = await response.text();
  const $ = cheerio.load(html);
  const ld = jsonLd($);
  const title = clean($("meta[property='og:title']").attr("content")) || clean($("h1").first().text()) || clean($("title").text());
  const subtitle = clean($("meta[property='og:description']").attr("content")) || undefined;

  // Remove page chrome before evaluating content candidates.
  $("script,style,noscript,iframe,svg,nav,header,footer,aside,form,[aria-hidden='true'],[role='navigation'],[role='complementary'],[class*='share'],[class*='social'],[class*='related'],[class*='recommend'],[class*='newsletter'],[class*='comment'],[class*='advert'],[class*='banner']").remove();
  const selectors = [
    "[itemprop='articleBody']",
    "article",
    "main article",
    "main",
    "[class*='article-body']",
    "[class*='content-body']",
    "[class*='post-body']"
  ];

  let best: string[] = [];
  for (const selector of selectors) {
    $(selector).each((_, el) => {
      const paragraphs = $(el).find("p").map((__, p) => clean($(p).text())).get().filter(p => p.length >= 35);
      const unique = [...new Set(paragraphs)];
      if (unique.join(" ").length > best.join(" ").length) best = unique;
    });
    if (best.length >= 5) break;
  }

  if (best.length < 2) {
    const candidates: string[] = [];
    $("p").each((_, p) => {
      const text = clean($(p).text());
      if (text.length >= 50) candidates.push(text);
    });
    best = [...new Set(candidates)];
  }

  return {
    title,
    subtitle,
    author: ld.author || clean($("[rel='author'],[itemprop='author']").first().text()) || undefined,
    publishedAt: ld.publishedAt || clean($("meta[property='article:published_time'],[itemprop='datePublished'],time[datetime]").first().attr("content") || $("time[datetime]").first().attr("datetime")) || undefined,
    updatedAt: ld.updatedAt || clean($("meta[property='article:modified_time'],[itemprop='dateModified']").first().attr("content")) || undefined,
    image: imageUrl($, url),
    paragraphs: best
  };
}
