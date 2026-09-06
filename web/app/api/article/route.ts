import { NextRequest, NextResponse } from "next/server";
import { crawlGEArticle } from "../../../lib/ge-article";

export const runtime = "nodejs";
export const revalidate = 120;

export async function GET(request: NextRequest) {
  const url = request.nextUrl.searchParams.get("url");
  if (!url) return NextResponse.json({ error: "Informe ?url=" }, { status: 400 });
  let parsed: URL;
  try { parsed = new URL(url); } catch { return NextResponse.json({ error: "URL inválida" }, { status: 400 }); }
  if (!parsed.hostname.endsWith("globo.com")) return NextResponse.json({ error: "Apenas URLs Globo são aceitas nesta versão." }, { status: 403 });

  try {
    return NextResponse.json(await crawlGEArticle(parsed.toString()));
  } catch (error) {
    const message = error instanceof Error ? error.message : "Falha ao extrair a matéria";
    return NextResponse.json({ error: message }, { status: 502 });
  }
}
