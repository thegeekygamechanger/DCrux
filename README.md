# DCrux

The daily crux of current affairs for UPSC and State PSC aspirants, on your phone.

Follow YouTube channels and get each video as readable notes. Read the day's papers with
every headline scored for your exam, and ask questions about any article. Test yourself on
the news with Prelims MCQs, or write Mains answers on paper and have them read and marked.
Wind down with generated soundscapes.

No account. Your data is kept on the device.

Developed by **Harsh Kumar** · [theharshkumar.in](https://theharshkumar.in)

## Install

Download the latest `DCrux-<version>.apk` from
[Releases](https://github.com/thegeekygamechanger/DCrux/releases/latest) and open it on your
phone. Android 7.0 or newer. No API key is needed.

After that the app updates itself: it checks this repository's latest release once a day
(or on request in **Settings → App update**) and hands the new build to Android to install.

### On a computer (Windows and Linux)

Each release also carries the computer app, built by GitHub from this same source:

| System | File |
| --- | --- |
| Windows | `DCrux-<version>-windows-x64-setup.exe` (or the `.msi`) |
| Debian, Ubuntu, Mint | `DCrux-<version>-linux-x64.deb` |
| Any Linux | `DCrux-<version>-linux-x64.tar.gz`: unpack and run `DCrux/bin/DCrux` |

It is the same app, compiled from the same source files as the phone app. A wide window gets a
landscape layout (navigation rail, lists in columns, newspaper pages set like a broadsheet);
made narrow, it becomes the phone layout.

### Sync across your devices

No account. In **Settings → Sync across devices** turn sync on: the sheet shows a QR code and,
under it, a 4-character code. On the other device open the same sheet and either type the four
characters or (on a phone) scan the QR code. From then on channels, video notes, questions and
answers, test results and study settings are the same on every paired device:

- **End-to-end encrypted.** Each group of devices shares a long random key that is made on the
  first device and never shown; everything is encrypted with it (AES-256-GCM) before it leaves.
  The service passes on data it cannot read, under an address also derived from that key.
- **The 4-character code is not the key.** It is good once and for a few minutes. The two devices
  use it to agree a fresh strong key that only someone who knows the code can agree (SPEKE, a
  password-authenticated key exchange over a 2048-bit group), and the real key crosses locked
  with that. Watching the exchange gives nothing to test guesses against; a wrong guess uses the
  code up and a new one is shown; and the service rations attempts. The QR code carries the key
  itself and never touches the service, so show both only to your own devices.
- **Relayed, not stored.** The service keeps the latest encrypted copy in memory only, so a device
  that was off can catch up, and writes none of it to disk. If the service restarts, the next
  device to open simply puts its copy back; the devices are where the data lives.
- **Immediate and light.** Opening the app syncs at once. While it is open it keeps one idle
  connection on which the service says "something changed", so a change on one device reaches
  the others within seconds; data moves only when something actually changed, and a device that
  is already up to date is told so in a few bytes.
- **Merged, not overwritten.** Devices take the union of what each has and the newer of two
  versions; deletions are remembered so they stay deleted.
- **Never uploaded:** PDFs and photos of answer pages.
- **Turn off on all my devices** clears the relayed copy and switches sync off on the others.

Video notes are written on the phone (the computer build has no embedded player to read captions
from) and arrive on the computer through sync.

## What it does

| Area | What you get |
| --- | --- |
| **Crux** | Each YouTube channel you add becomes a card with its history by date, paginated. The header shows the date and, if you set a city, its temperature and weather alerts. Pull down to sync. The date, time and the day's public-domain quotations sit on one card over a drawn landscape that follows the hour and the weather. |
| **Video** | YouTube's own player with all its controls, plus a Summary / Detailed toggle written from what is said in the video (its captions), with photos, diagrams and an exam-relevance score. |
| **Read mode** | Video notes open as a book: a cover with the contents, then one page per topic, turned like paper. Full screen available. |
| **Papers** | Headlines live from The Hindu, The Indian Express, PIB and more, laid out as newspaper pages. Articles and e-papers open on the publisher's own site in a no-cache in-app browser with an ad blocker and read mode. |
| **Ask Crux AI** | On any headline, on any video's notes and in read mode (full screen too), ask a question in a slide-up sheet. The answer comes from the article or video first, within a couple of seconds; only when that does not cover it does Crux AI search the web and list the pages it used, and it says so when it cannot confirm an answer. Answers can include diagrams and pictures and suggest follow-up questions. Every question stays saved with what it was asked on. |
| **History** | Settings → History lists every question asked (when, and on which video or article) and every test, with filters and pages. |
| **Tests: Prelims** | Multiple-choice tests generated from the day's headlines for the exam and difficulty you choose, each question with a relevance score. Retake a test to beat your score. Results earn stars, are stored encrypted, and can be viewed or shared as a PDF made in memory and never saved. |
| **Tests: Mains** | Descriptive questions set from the day's news. Write on paper against the clock, photograph or upload the pages, and Crux AI reads the handwriting and marks it like a mentor: a marked copy (each section with a tick or cross and a short remark), marks by criterion, mistakes quoted from your answer, what to fix first, a topper's outline and time advice. The reading it marked from is always shown. |
| **Mind Flow** | Fifteen soothing sounds (rain, ocean, fire, binaural tones and more), synthesised on the phone in real time. Mix them, set a sleep timer, control playback from the notification. |
| **Search** | Slides up over any screen and searches everything synced, with an optional AI overview written only from the matching results. |
| **Privacy** | App lock with biometrics and a PIN. Backups are off. No analytics. Location is optional and city-level only. The in-app Privacy sheet lists exactly what leaves the phone. |

Kotlin, Jetpack Compose, Material 3 Expressive.

## How it stays honest

- **No invented news.** Headlines, snippets and links come verbatim from the publishers' feeds. The AI only rates and annotates what it is given, and is told to say so when the material is thin.
- **Summaries are of the video itself, and checked.** They are written from the video's captions, loaded through the official player, as study notes organised by concept. A second pass then checks every line against the captions and corrects or removes what they do not support; where a figure is unclear in the video the notes say so. Nothing is written until you say Continue, and when a video has no captions the app says so instead of summarising its description.
- **Everything Crux AI writes can be stopped.** A Stop button sits wherever something is being generated.
- **One score, out of 100.** Exam relevance is the sum of four parts (syllabus fit 40, likelihood of a question 30, depth 20, currency 10), built the same way for videos, headlines and test questions.
- **Mains marking shows its working.** The AI first copies out what it can read from the pages, writing `[illegible]` where it cannot, and marks only that. Marks are the sum of five fixed criteria, capped in the app, and marked strictly. Time advice uses the time the app recorded, against the UPSC pace of 250 marks in three hours. It is an estimate to learn from, not an official score.
- **Weather alerts are labelled for what they are.** They are worked out from Open-Meteo forecast data with fixed thresholds and are not official warnings.
- **Air quality is India's own index, from the official monitors where there are any.** For Indian cities the AQI is the Central Pollution Control Board's, read from its monitoring stations through data.gov.in: the city figure is the average of its stations, shown with the worst station and the day's highest reading. Where a city has no monitor, and outside India, the app shows an estimate on the same scale from modelled particle levels and says that it is an estimate.
- **Sync does not depend on one door.** YouTube's channel feed goes missing now and then; DCrux then reads the channel's own Videos and Live pages instead of reporting an error.
- **Publishers keep their content.** DCrux stores a headline index (headline, short feed snippet, link). Full articles and e-papers are read on the publisher's site; paid e-papers need your own subscription.
- **YouTube plays in YouTube's player.** DCrux adds no ads of its own and does not strip YouTube's.
- **Pictures are openly licensed.** Photos come from Wikimedia Commons (public domain, CC0, CC BY, CC BY-SA) and are shown with author and licence.

AI-written text can be wrong. Check important facts against the original video or article.

## Build

Requirements: JDK 17+, Android SDK with platform 37 and build-tools 36.

```bash
./gradlew :app:assembleDebug        # debug APK in app/build/outputs/apk/debug/
./gradlew :app:assembleRelease      # release APK (signed with keystore.properties if present)
```

### Where the AI key lives

The AI features use the Claude API. There are three ways to build the app:

| Build | Key | For |
| --- | --- | --- |
| Plain (no `secrets.properties`) | Each user pastes their own in **Settings → AI**; it is stored encrypted with the Android Keystore | Anyone building from source |
| Service (`-PnoBakedKey=true` with `DCRUX_PROXY_URL` and `DCRUX_APP_SECRET` set) | None in the app; requests go through the relay in [`server/`](server/README.md), which holds the key and applies daily limits. The relay also passes the encrypted sync data between a person's devices (held in memory, never written to disk) and hands out the request for official air-quality readings. | A public release that works out of the box |
| Personal (`CLAUDE_API_KEY` in `secrets.properties`) | Baked into the APK, only lightly masked | Your own phone only |

`secrets.properties` lives in the project root and is git-ignored:

```properties
CLAUDE_API_KEY=sk-ant-...
DCRUX_PROXY_URL=https://your.host
DCRUX_APP_SECRET=...
```

A key inside an APK cannot be protected: anyone with the file can recover it. Never publish or
share a personal build. For a public build use the service, and set its daily limits to what you
are prepared to pay for.

### Release signing

Create `keystore.properties` in the project root (git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Every release must be signed with the same keystore, or Android will refuse the update.

### Publishing an update

Publishing a GitHub release is the whole update flow; no server of ours ever holds an installer.

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts` and `appVersion` in `desktop/build.gradle.kts`, commit and push.
2. Write `release-notes/<version>.md`.
3. Run `GITHUB_PAT=... scripts/release.sh`. It builds the public APK (no key inside), refuses to go on
   if a key is found in it, then creates the release and uploads `DCrux-<version>.apk`.
4. On GitHub, run **Actions → Desktop installers** with the release tag. It builds the Windows `.exe`
   and `.msi` and the Linux `.deb` and `.tar.gz`, starts each packaged app off-screen to check that it
   runs, and attaches them to the release. It needs the repository secrets `DCRUX_PROXY_URL` and
   `DCRUX_APP_SECRET`.

### The desktop app

```bash
./gradlew :desktop:run                    # run it
./gradlew :desktop:createDistributable    # the app with its own Java runtime, in desktop/build/compose/binaries
./gradlew :desktop:packageDeb             # or packageMsi / packageExe on Windows
./gradlew :desktop:syncCheck              # two simulated devices pair and sync through a local relay (see the task's comment)
./gradlew :desktop:render -Pshots="<data dir> <out dir> nosync wait:2500 first"   # draw it off-screen to a PNG
```

It compiles the files under `app/src/main/java` (minus the Android-only ones listed in
`desktop/build.gradle.kts`) together with its own `Desktop*.kt` counterparts. `DCRUX_DESKTOP_ONLY=1`
leaves the Android app out, so no Android SDK is needed; the GitHub workflow in
`.github/workflows/desktop.yml` builds the installers that way and attaches them to each release.

## Licence

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). The in-app About screen
lists every library, font and service with its licence.
