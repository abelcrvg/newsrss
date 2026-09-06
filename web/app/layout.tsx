import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "NewsRSS",
  description: "Agregador de notícias do NewsRSS",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="pt-BR">
      <body>{children}</body>
    </html>
  );
}
