# DCrux service

A small relay that keeps the Claude API key on a server, so the public app never carries one.

An app cannot keep a secret: anything inside an APK can be pulled out, however it is encrypted.
So the public build of DCrux holds no key. It sends its two kinds of AI request to this relay,
and the relay adds the key and forwards them.

## What it allows

- `GET /v1/models` and `POST /v1/messages`, streamed back as they arrive, and `GET /v1/aqi` (official air quality for a city, when a data.gov.in key is set).
- Only the models in `DCRUX_MODELS`, a cap on `max_tokens` and request size, and no tools except
  the API's own web search (a few searches per answer, for Ask AI when the material lacks the answer).
- Only text and inline pictures (the photographed pages of a Mains answer) as content; nothing
  that would make the API fetch a URL or run a tool.
- Only requests signed by the app (install id + time + body hash, HMAC with `DCRUX_APP_SECRET`),
  each signature accepted once, so a captured request cannot be replayed.
- Daily limits per install, per network address and overall, and a cap on how many requests one
  install may have running at once. When a limit is hit the app shows the relay's message.

The defaults are set high enough that no real user meets them: they are circuit breakers
against abuse, not a ration. Responses are streamed straight through and connections to the API
are kept open, so the relay adds almost no delay. It cannot remove the API account's own rate
limits; when those are reached the app falls back to the next model and retries.

The signing secret is inside the app, so a determined person can extract it. It stops casual
misuse; **the daily limits and a spend limit on the API account are what protect the bill**.

Nothing about a request's text is logged or stored: the log has time, path, status, duration,
a hash of the install id, the model and the request size.

## Settings (environment)

| Variable | Default | Meaning |
| --- | --- | --- |
| `ANTHROPIC_API_KEY` | required | The key the relay calls the API with |
| `DCRUX_APP_SECRET` | required | Shared with the app build (`secrets.properties`) |
| `PORT`, `HOST` | `8787`, `127.0.0.1` | Where it listens; put nginx or another TLS front in front |
| `DCRUX_MODELS` | opus-5-5, sonnet-5-5, haiku-5-5 | Comma-separated allow-list |
| `DCRUX_DEVICE_DAILY` | `1000` | Requests per install per day |
| `DCRUX_ADDRESS_DAILY` | `20000` | Requests per network address per day (mobile networks share addresses) |
| `DCRUX_GLOBAL_DAILY` | `100000` | Requests per day in total |
| `DCRUX_DEVICE_CONCURRENT` | `8` | Requests one install may have running at once |
| `DCRUX_MAX_TOKENS` | `32000` | Ceiling on `max_tokens` |
| `DCRUX_MAX_BODY_BYTES` | `12000000` | Largest request accepted; the TLS front must allow the same |
| `DCRUX_MAX_IMAGES` | `8` | Pictures allowed in one request |
| `DCRUX_MAX_SEARCHES` | `3` | Web searches one answer may make (billed per search by the API) |
| `DCRUX_STATE_FILE` | none | Where to keep the day's counters across restarts |
| `DATAGOV_API_KEY` | none | Your free key from data.gov.in. With it, `GET /v1/aqi?city=&lat=&lon=` answers with India's official AQI for the city from the CPCB monitors (cached for an hour). Without it the app shows its own estimate. |

Days are counted in India time.

## Run

```bash
ANTHROPIC_API_KEY=sk-ant-... DCRUX_APP_SECRET=... node dcrux-proxy.mjs
```

Node 18 or newer, no dependencies. In production run it under systemd as its own user, with the
settings in a root-only environment file, behind a TLS reverse proxy with response buffering off.

## Point the app at it

In the app project's git-ignored `secrets.properties`:

```properties
DCRUX_PROXY_URL=https://your.host
DCRUX_APP_SECRET=the-same-secret
```

Then build the public APK with no key inside:

```bash
./gradlew :app:assembleRelease -PnoBakedKey=true
```
