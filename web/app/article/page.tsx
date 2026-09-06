"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import type { ArticleContent } from "../../lib/article";
import "../styles.css";

function date(value?: string) {
  if (!value) return "";
  return new Date(value).toLocaleString("pt-BR", { dateStyle: "medium", timeStyle: "short" });
}

function ArticleContentView() {
  const params = useSearchParams();
  const url = params.get("url");
  const [article, setArticle] = useState<ArticleContent | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!url) { setError("Matéria não informada"); return; }
    fetch(`/api/article?url=${encodeURIComponent(url)}`)
      .then(async response => {
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || "Falha ao abrir a matéria");
        return data as ArticleContent;
      })
      .then(setArticle)
      .catch(error => setError(error instanceof Error ? error.message : "Falha ao abrir a matéria"));
  }, [url]);

  if (error) return <main className="reader"><a className="back" href="/">← Voltar</a><section className="error">{error}</section></main>;
  if (!article) return <main className="reader"><a className="back" href="/">← Voltar</a><p>Carregando matéria…</p></main>;

  return (
    <main className="reader">
      <a className="back" href="/">← Voltar às notícias</a>
      <article className="article">
        <div className="article-source">NewsRSS · leitura limpa</div>
        <h1>{article.title}</h1>
        {article.subtitle && <p className="article-lead">{article.subtitle}</p>}
        <div className="article-meta">
          {article.author && <span>{article.author}</span>}
          {article.publishedAt && <span>{date(article.publishedAt)}</span>}
          {article.updatedAt && article.updatedAt !== article.publishedAt && <span>Atualizado {date(article.updatedAt)}</span>}
        </div>
        {article.image && <img className="article-image" src={article.image} alt="" />}
        <div className="article-body">
          {article.paragraphs.map((paragraph, index) => <p key={`${index}-${paragraph.slice(0, 20)}`}>{paragraph}</p>)}
        </div>
        <a className="original" href={url || "#"} target="_blank" rel="noreferrer">Abrir matéria original ↗</a>
      </article>
    </main>
  );
}

export default function ArticlePage() {
  return (
    <Suspense fallback={<main className="reader"><a className="back" href="/">← Voltar</a><p>Carregando matéria…</p></main>}>
      <ArticleContentView />
    </Suspense>
  );
}
