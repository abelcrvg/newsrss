import { NextResponse } from "next/server";
import { extractArticle } from "../../../lib/article";

export const runtime = "nodejs";

export async function GET(request: Request) {
  const url = new URL(request.url).searchParams.get("url");
  if (!url) return NextResponse.json({ error: "Informe a URL da matéria" }, { status: 400 });
  try {
    const article = await extractArticle(url);
    return NextResponse.json(article, { headers: { "cache-control": "public, s-maxage=120, stale-while-revalidate=300" } });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Falha ao abrir a matéria";
    return NextResponse.json({ error: message }, { status: 502 });
  }
}
