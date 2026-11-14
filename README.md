# DCrux

The daily crux of current affairs for UPSC and State PSC aspirants, on your phone.
Paste YouTube channels, read the day's papers, get AI summaries and exam relevance,
test yourself on the news, and wind down with generated soundscapes.

No account. No server. Everything runs in the app and stays on the device.

Developed by **Harsh Kumar** · [theharshkumar.in](https://theharshkumar.in)

## What it does

| Area | What you get |
| --- | --- |
| **Crux** | Each YouTube channel you add becomes a card with its history by date, paginated. A daily "spark" carousel pairs public-domain quotations with artwork painted in code; both change every day. |
| **Video** | The official YouTube player with all its controls, plus a Summary / Detailed toggle written by AI from what is said in the video (its captions), and a per-exam relevance score. |
| **Papers** | Headlines live from The Hindu, The Indian Express, PIB and more, laid out as newspaper pages with a page-turn animation. Each headline is scored for your exams. Articles and e-papers open on the publisher's own site in a no-cache in-app browser with an ad blocker and a read-mode button. |
| **Tests** | Multiple-choice tests generated from the day's headlines for the exam and difficulty you choose. Results earn stars, are stored encrypted, and can be viewed or shared as a PDF that is generated in memory and never saved. |
| **Mind Flow** | Fifteen soothing sounds (rain, ocean, fire, binaural tones and more), all synthesised on the phone in real time. Mix them, set a sleep timer, and control playback from the notification. |
| **Search** | Search everything synced, with an optional AI overview written only from the matching results. |
| **Settings** | Add channels by pasting YouTube links, pick your exams and sources, theme, model choice, app lock. |
| **Privacy** | App lock with biometrics and a PIN. The AI key is encrypted with the Android Keystore. Backups are off, so nothing leaves the phone. |

Android 7.0 (API 24) and newer. Kotlin, Jetpack Compose, Material 3 Expressive.

## How it stays honest

- **No invented news.** Headlines, snippets and links come verbatim from the publishers' feeds. Claude only rates and annotates what it is given, and is told to say so when the material is thin.
- **Summaries are of the video itself.** They are written from the video's captions, loaded through the official player. When a video has no captions the app says so instead of summarising its description.
- **Publishers keep their content.** DCrux stores a headline index (headline, short feed snippet, link). Full articles and e-papers are read on the publisher's site; paid e-papers need your own subscription.
- **YouTube plays in YouTube's player.** DCrux adds no ads of its own and does not strip YouTube's.

## Build

Requirements: JDK 17+, Android SDK with platform 37 and build-tools 36.

```bash
./gradlew :app:assembleDebug        # debug APK in app/build/outputs/apk/debug/
./gradlew :app:assembleRelease      # release APK (signed with keystore.properties if present)
```

### Claude API key

The AI features use the Claude API. By default the app asks for a key in **Settings → AI** (Test API, then Save API key).

For a personal build you can bake a key in so the app never asks and shows no key controls. Create `secrets.properties`
in the project root (it is git-ignored):

```properties
CLAUDE_API_KEY=sk-ant-...
```

A baked-in key is only lightly masked inside the APK. Anyone who gets that APK file can
recover it, so never publish or share an APK built this way, and set a spend limit on the key.

### Release signing

Create `keystore.properties` in the project root (git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Project layout

```
app/src/main/java/app/dcrux/
  data/      Room database, settings, Keystore-backed secure store
  net/       YouTube feeds, news feeds, HTTP
  ai/        Claude Messages API client (streaming) and prompts
  sync/      Daily WorkManager sync and manual refresh
  audio/     Procedural sound engine and playback service
  pdf/       In-memory PDF layout and streaming share provider
  lock/      Biometric and PIN app lock
  ui/        Compose screens and theme
```

## Licence

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). The in-app About screen
lists every library, font and service with its licence.

AI-written text can be wrong. Check important facts against the original video or article.
