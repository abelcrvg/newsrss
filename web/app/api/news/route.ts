import { NextResponse } from "next/server";
import { crawlAll } from "../../../lib/news";

export const runtime = "nodejs";
export const revalidate = 120;

export async function GET() {
  try {
    const items = await crawlAll();
    return NextResponse.json({ source: "NewsRSS", fetchedAt: new Date().toISOString(), count: items.length, items });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Falha ao consultar as fontes";
    return NextResponse.json({ error: message }, { status: 502 });
  }
}
