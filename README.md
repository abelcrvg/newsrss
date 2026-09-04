# NewsRSS

Open-source Android news reader focused on clean, comfortable reading.

## Goal

NewsRSS aggregates news from RSS/Atom feeds and, when a feed is available, follows the original article URL to extract the full publicly available article into a distraction-free reading experience.

The reader should preserve useful editorial structure such as:

- title and subtitle;
- author and publication date;
- hero image and captions;
- paragraphs and section headings;
- quotations;
- ordered and unordered lists.

It should remove page chrome such as navigation, advertising, popups, social widgets, comments and unrelated recommendations whenever the extraction algorithm can identify them reliably.

## Architecture

The Android application is built with Kotlin and Jetpack Compose. The project is intentionally split into independent layers so that RSS discovery, article extraction, persistence and UI can evolve separately.

```text
app/
├── core/
│   ├── extraction/   # Full-article extraction and reader normalization
│   ├── feed/         # RSS/Atom discovery and parsing
│   └── model/        # Source-independent domain models
├── data/             # Persistence and remote data sources (next stages)
├── ui/               # Compose screens and design system
└── MainActivity.kt
```

## Planned pipeline

```text
Site / RSS / Atom
        ↓
Feed discovery
        ↓
Article URL
        ↓
HTML retrieval
        ↓
Content extraction
        ↓
Reader normalization
        ↓
Clean article UI
```

Site-specific extraction rules can be added later for sources where generic extraction is not sufficient.

## Project status

Early development. The current repository contains the Android foundation and the first domain contracts. Networking, RSS parsing, HTML extraction, persistence and the complete reader UI will be implemented incrementally.

## License

To be defined before the first public release.
