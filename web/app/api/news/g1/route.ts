import { NextResponse } from "next/server";
import { crawlG1 } from "../../../../lib/crawlers/g1";

export const runtime = "nodejs";
export const revalidate = 120;

export async function GET() {
  try {
    const items = await crawlG1();
    return NextResponse.json({
      source: "G1",
      fetchedAt: new Date().toISOString(),
      count: items.length,
      items
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Falha ao consultar o G1";
    return NextResponse.json({ error: message }, { status: 502 });
  }
}
