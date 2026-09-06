export type NewsCategory = "news" | "football" | "technology" | "games" | "international" | "english";

export type NewsItem = {
  id: string;
  source: string;
  category: NewsCategory;
  title: string;
  subtitle?: string;
  url: string;
  image?: string;
  publishedAt?: string;
  updatedAt?: string;
  author?: string;
};
