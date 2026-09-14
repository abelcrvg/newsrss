# NewsRSS

Open-source Android news reader focused on clean, comfortable reading.

## What it does

NewsRSS combines RSS/Atom feeds and source-specific homepage crawlers, normalizes discovered stories, caches them locally and publishes validated feed items to a Supabase ingestion endpoint.

When a reader opens an article, NewsRSS extracts the publicly available article content into a distraction-free reader and can translate supported English sources on-device.

The reader preserves useful editorial structure such as:

- title and subtitle;
- author and publication date;
- hero image;
- paragraphs and section headings;
- quotations;
- ordered and unordered lists;
- a short extractive quick summary when enough article text is available.

It removes page chrome such as navigation, advertising, popups, social widgets, comments and unrelated recommendations whenever the extraction strategy can identify them reliably.

## Architecture

The Android application is built with Kotlin and Jetpack Compose. Ingestion, extraction, persistence, background refresh and UI are kept as separate concerns.

```text
SourceCatalog
     ↓
SourceReaderRegistry
 ┌───┴───────────────┐
 RSS / Atom     Site crawler
 └───┬───────────────┘
     ↓
 FeedItem + canonical URL
     ↓
 Local cache + Supabase ingestion

Article URL
     ↓
ExtractionProfiles
     ↓
Generic / site-aware Jsoup extraction
     ↓
ExtractionMetadata + confidence
     ↓
Offline quick summary
     ↓
Reader UI
```

### Source ingestion

Each source has its own refresh interval. Fast-moving sources can refresh every 5 minutes, normal sources every 15 minutes and slower technology/games sources every 30 minutes. Background refresh uses bounded concurrency so a large source catalog does not create an uncontrolled number of simultaneous requests.

`SourceReaderRegistry` keeps site-specific crawler selection out of the UI and high-level feed reader. RSS remains the generic fallback while sources such as G1, GE, UOL, TecMundo, Voxel, IGN, The Verge, Sky Sports and ESPN can use dedicated strategies.

### Article extraction

`JsoupArticleExtractor` uses extraction profiles for important publishers and a generic fallback for unknown sites. Every extraction receives a confidence score and metadata including the selected strategy, word count, image count and warnings.

The reader also creates a small extractive summary locally. It does not send article text to an external AI service merely to produce the quick summary.

### Persistence and sync

The Android cache keeps recent stories locally. URLs are canonicalized to remove common tracking parameters before cache deduplication.

Supabase acts as the shared news ingestion layer. The mobile application only uses the publishable API key; privileged database access remains inside the Edge Function. The ingestion endpoint validates, limits and deduplicates incoming items before writing to `news_items`.

## Project status

The Android foundation, feed ingestion, source-specific crawling, article extraction, local caching, background refresh, translation, reader UI and Supabase synchronization are implemented. The project is still under active development, with additional source-specific extraction rules and richer personalization planned.

## Security notes

Publishable Supabase keys are expected to be public in mobile applications. They must never be granted privileged database permissions. The `news-sync` Edge Function is the only component that uses the project's secret key to write to the database.

## License

To be defined before the first public release.
