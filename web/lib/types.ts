export type NewsItem = {
  id: string;
  source: "GE" | "G1";
  title: string;
  subtitle?: string;
  url: string;
  image?: string;
  publishedAt?: string;
  updatedAt?: string;
};
