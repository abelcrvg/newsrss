import type { NewsCategory } from "./types";

export type SourceConfig = {
  id: string;
  name: string;
  url: string;
  category: NewsCategory;
};

export const SOURCES: SourceConfig[] = [
  { id: "ge", name: "GE", url: "https://ge.globo.com/", category: "football" },
  { id: "g1", name: "G1", url: "https://g1.globo.com/", category: "news" },
  { id: "uol", name: "UOL", url: "https://www.uol.com.br/", category: "news" },
  { id: "tecmundo", name: "TecMundo", url: "https://www.tecmundo.com.br/", category: "technology" },
  { id: "voxel", name: "Voxel", url: "https://www.tecmundo.com.br/voxel/", category: "games" },
  { id: "ign-brasil", name: "IGN Brasil", url: "https://br.ign.com/", category: "games" },
  { id: "the-verge", name: "The Verge", url: "https://www.theverge.com/", category: "english" }
];
