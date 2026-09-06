export const USER_AGENT = "NewsRSS/2.0 (+https://github.com/abelcrvg/newsrss)";

export async function fetchHtml(url: string) {
  const response = await fetch(url, {
    headers: {
      "user-agent": USER_AGENT,
      accept: "text/html,application/xhtml+xml"
    },
    next: { revalidate: 120 }
  });
  if (!response.ok) throw new Error(`Fonte respondeu HTTP ${response.status}`);
  return response.text();
}

export function absoluteUrl(value: string, base: string) {
  try { return new URL(value, base).toString(); } catch { return ""; }
}

export function cleanText(value?: string) {
  return value?.replace(/\s+/g, " ").trim() || "";
}
