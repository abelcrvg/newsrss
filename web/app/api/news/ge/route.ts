import { NextResponse } from "next/server";
import { crawlGE } from "../../../../lib/crawlers/ge";

export const runtime = "nodejs";
export const revalidate = 120;

export async function GET() {
  try {
    const items = await crawlGE();
    return NextResponse.json({ source: "GE", fetchedAt: new Date().toISOString(), count: items.length, items });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Falha ao consultar o GE";
    return NextResponse.json({ error: message }, { status: 502 });
  }
}
