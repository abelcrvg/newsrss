import { NextResponse } from "next/server";
import { extractArticle } from "../../../lib/article";
import { SOURCES } from "../../../lib/sources";

export const runtime = "nodejs";

function isAllowedArticleUrl(value: string): boolean {
  try {
    const url = new URL(value);
    if (url.protocol !== "https:") return false;
    const host = url.hostname.toLowerCase().replace(/^www\./, "");
    return SOURCES.some(source => {
      try {
        const sourceUrl = new URL(source.url);
        const sourceHost = sourceUrl.hostname.toLowerCase().replace(/^www\./, "");
        return host === sourceHost || host.endsWith(`.${sourceHost}`);
      } catch {
        return false;
      }
    });
  } catch {
    return false;
  }
}

export async function GET(request: Request) {
  const url = new URL(request.url).searchParams.get("url");
  if (!url) return NextResponse.json({ error: "Informe a URL da matéria" }, { status: 400 });
  if (!isAllowedArticleUrl(url)) {
    return NextResponse.json({ error: "Fonte não autorizada para leitura interna" }, { status: 403 });
  }
  try {
    const article = await extractArticle(url);
    return NextResponse.json(article, { headers: { "cache-control": "public, s-maxage=120, stale-while-revalidate=300" } });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Falha ao abrir a matéria";
    return NextResponse.json({ error: message }, { status: 502 });
  }
}
