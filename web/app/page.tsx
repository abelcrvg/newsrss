"use client";

import { useEffect, useMemo, useState } from "react";
import type { NewsCategory, NewsItem } from "../lib/types";
import "./styles.css";

type Filter = "all" | NewsCategory;

const FILTERS: { id: Filter; label: string }[] = [
  { id: "all", label: "Todas" },
  { id: "news", label: "Notícias" },
  { id: "football", label: "Futebol" },
  { id: "technology", label: "Tecnologia" },
  { id: "games", label: "Jogos" },
  { id: "international", label: "Internacional" },
  { id: "english", label: "Em inglês" }
];

function relativeDate(value?: string) {
  if (!value) return "Horário indisponível";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Horário indisponível";
  const diff = Math.max(0, Date.now() - date.getTime());
  if (diff < 60 * 60 * 1000) return `há ${Math.max(1, Math.floor(diff / 60000))} min`;
  if (diff < 24 * 60 * 60 * 1000) return `há ${Math.floor(diff / 3600000)} h`;
  return date.toLocaleString("pt-BR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" });
}

export default function Home() {
  const [items, setItems] = useState<NewsItem[]>([]);
  const [filter, setFilter] = useState<Filter>("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  async function load() {
    setLoading(true);
    setError("");
    try {
      const response = await fetch("/api/news", { cache: "no-store" });
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || "Não foi possível carregar as notícias");
      setItems(Array.isArray(data.items) ? data.items : []);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Erro ao carregar notícias");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, []);

  const visibleItems = useMemo(
    () => filter === "all" ? items : items.filter(item => item.category === filter),
    [filter, items]
  );

  return (
    <main className="shell">
      <header className="header">
        <div>
          <div className="eyebrow">NEWSRSS V2</div>
          <h1>Notícias</h1>
          <p>Um só lugar para acompanhar todas as fontes.</p>
        </div>
        <button className="refresh" onClick={load} disabled={loading}>
          {loading ? "Atualizando…" : "Atualizar"}
        </button>
      </header>

      <nav className="chips" aria-label="Categorias">
        {FILTERS.map(option => (
          <button
            className={`chip ${filter === option.id ? "active" : ""}`}
            key={option.id}
            onClick={() => setFilter(option.id)}
          >
            {option.label}
          </button>
        ))}
      </nav>

      {error && <section className="error">{error}</section>}
      {loading && (
        <section className="grid" aria-label="Carregando">
          {Array.from({ length: 8 }).map((_, i) => <div className="skeleton" key={i} />)}
        </section>
      )}

      {!loading && !error && visibleItems.length === 0 && (
        <section className="error">Nenhuma notícia encontrada nesta categoria.</section>
      )}

      {!loading && !error && visibleItems.length > 0 && (
        <section className="grid">
          {visibleItems.map((item, index) => (
            <article className={index === 0 ? "card featured" : "card"} key={item.id}>
              {item.image && <img src={item.image} alt="" loading={index < 3 ? "eager" : "lazy"} />}
              <div className="content">
                <div className="meta">
                  <strong>{item.source}</strong>
                  <span>{relativeDate(item.publishedAt)}</span>
                </div>
                <h2>{item.title}</h2>
                {item.subtitle && <p>{item.subtitle}</p>}
                {item.author && <small>Por {item.author}</small>}
                {item.updatedAt && item.updatedAt !== item.publishedAt && <small>Atualizado {relativeDate(item.updatedAt)}</small>}
                <a href={item.url} target="_blank" rel="noreferrer">Ler notícia →</a>
              </div>
            </article>
          ))}
        </section>
      )}
    </main>
  );
}
