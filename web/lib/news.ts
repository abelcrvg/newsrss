import { crawlGenericHomepage } from "./crawlers/base";
import { crawlG1 } from "./crawlers/g1";
import { crawlGE } from "./crawlers/ge";
import { SOURCES } from "./sources";
import type { NewsItem } from "./types";

export async function crawlSource(sourceId: string): Promise<NewsItem[]> {
  const source = SOURCES.find(item => item.id === sourceId);
  if (!source) throw new Error(`Fonte desconhecida: ${sourceId}`);
  if (source.id === "ge") return crawlGE();
  if (source.id === "g1") return crawlG1();
  return crawlGenericHomepage(source);
}

export async function crawlAll(): Promise<NewsItem[]> {
  const results = await Promise.allSettled(SOURCES.map(source => crawlSource(source.id)));
  return results.flatMap(result => result.status === "fulfilled" ? result.value : []).sort((a, b) => {
    const left = a.publishedAt ? Date.parse(a.publishedAt) : 0;
    const right = b.publishedAt ? Date.parse(b.publishedAt) : 0;
    return right - left;
  });
}
