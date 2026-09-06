"use client";

import { useEffect, useState } from "react";
import type { NewsItem } from "../lib/types";
import "./styles.css";

function relativeDate(value?: string) {
  if (!value) return "Horário indisponível";
  const date = new Date(value);
  const diff = Date.now() - date.getTime();
  if (diff < 60 * 60 * 1000) return `há ${Math.max(1, Math.floor(diff / 60000))} min`;
  if (diff < 24 * 60 * 60 * 1000) return `há ${Math.floor(diff / 3600000)} h`;
  return date.toLocaleString("pt-BR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" });
}

export default function Home() {
  const [items, setItems] = useState<NewsItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  async function load() {
    setLoading(true);
    setError("");
    try {
      const response = await fetch("/api/news/ge", { cache: "no-store" });
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || "Não foi possível carregar o GE");
      setItems(data.items || []);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Erro ao carregar notícias");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, []);

  return (
    <main className="shell">
      <header className="header">
        <div>
          <div className="eyebrow">NEWSRSS V2</div>
          <h1>Notícias</h1>
          <p>Leitura limpa, organizada e atualizada.</p>
        </div>
        <button className="refresh" onClick={load} disabled={loading}>{loading ? "Atualizando…" : "Atualizar"}</button>
      </header>

      <nav className="chips"><button className="chip active">GE</button><button className="chip">Futebol</button><button className="chip">Todas</button></nav>

      {error && <section className="error">{error}</section>}
      {loading && <section className="grid">{Array.from({ length: 8 }).map((_, i) => <div className="skeleton" key={i} />)}</section>}

      {!loading && !error && <section className="grid">
        {items.map((item, index) => (
          <article className={index === 0 ? "card featured" : "card"} key={item.id}>
            {item.image && <img src={item.image} alt="" loading={index < 3 ? "eager" : "lazy"} />}
            <div className="content">
              <div className="meta"><strong>GE</strong><span>{relativeDate(item.publishedAt)}</span></div>
              <h2>{item.title}</h2>
              {item.subtitle && <p>{item.subtitle}</p>}
              {item.updatedAt && item.updatedAt !== item.publishedAt && <small>Atualizado {relativeDate(item.updatedAt)}</small>}
              <a href={item.url} target="_blank" rel="noreferrer">Ler no GE →</a>
            </div>
          </article>
        ))}
      </section>}
    </main>
  );
}
