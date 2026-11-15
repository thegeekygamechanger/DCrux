# DCrux service

A small relay that keeps the Claude API key on a server, so the public app never carries one.

An app cannot keep a secret: anything inside an APK can be pulled out, however it is encrypted.
So the public build of DCrux holds no key. It sends its two kinds of AI request to this relay,
and the relay adds the key and forwards them.

## What it does

- **AI:** `GET /v1/models` and `POST /v1/messages`, streamed back as they arrive.
  - Only the models in `DCRUX_MODELS`, a cap on `max_tokens` and request size, and no tools except
    the API's own web search (a few searches per answer, for Ask Crux AI when the material lacks the answer).
  - Only text and inline pictures (the photographed pages of a Mains answer) as content; nothing
    that would make the API fetch a URL or run a tool.
  - Daily limits per install, per network address and overall, and a cap on how many requests one
    install may have running at once. When a limit is hit the app shows the relay's message.
- **Sync across a person's devices:** `GET`/`PUT`/`DELETE /v1/sync/<id>` and `GET /v1/sync/<id>/wait`.
  - What is stored is already encrypted on the device with a key the relay never sees; the `<id>` is
    derived from the same pairing code. The relay can neither read the data nor tell whose it is.
  - Saves carry the version they were based on, so two devices saving at once merge instead of
    overwriting (`409`). An erase leaves a small marker so the other devices stop.
  - `/wait` is how sync is immediate without polling: the reply is held until the stored version
    changes or about 50 seconds pass. An idle held connection costs next to nothing; nothing is read
    from disk or sent while a person's data is not changing. `GET ...?have=<version>` answers `304`
    when the device already has that version.
- **Air quality:** `GET /v1/aqi?city=&lat=&lon=` for India's official AQI (CPCB monitors, via data.gov.in).
  data.gov.in answers only from inside India, so a relay hosted elsewhere hands the app the request
  to make and the app fetches the readings itself; the key is never inside the app package.
- **Every call is signed by the app** (install id + time + body hash, HMAC with `DCRUX_APP_SECRET`),
  and each signature is accepted once, so a captured request cannot be replayed.

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
| `DCRUX_SYNC_DIR` | none | A folder for sync across a person's devices. Each person's data arrives already encrypted with a key only their devices hold; the relay stores and returns it and cannot read it. Unset means sync is not offered. |
| `DCRUX_SYNC_MAX_TOTAL_BYTES` | `2000000000` | Ceiling on all stored sync data |
| `DCRUX_SYNC_MAX_WAITERS` | `20000` | Devices that may wait at once to be told of a change (`GET /v1/sync/<id>/wait`, an idle connection each); beyond it they are asked to check once a minute |
| `DCRUX_SYNC_WRITES_DAILY` | `600` | Sync uploads per person per day |
| `DATAGOV_API_KEY` | none | Your free key from data.gov.in, for India's official AQI from the CPCB monitors. data.gov.in only answers from inside India, so by default `GET /v1/aqi` gives the app (over the signed channel) the request to make and the app fetches the readings itself; the key is never inside the app package. Without the key the app shows its own estimate. |
| `DCRUX_AQI_SERVER_FETCH` | off | Set to `1` on a relay hosted in India: it then fetches the readings itself and serves one cached answer per city for an hour. |

Days are counted in India time.

## Run

```bash
ANTHROPIC_API_KEY=sk-ant-... DCRUX_APP_SECRET=... node dcrux-proxy.mjs
```

Node 18 or newer, no dependencies. The TLS front must allow request bodies as large as
`DCRUX_MAX_BODY_BYTES` and must not time out a quiet connection in under a minute (the sync
`/wait` call is held for up to 50 seconds); with nginx: `client_max_body_size 16m`,
`proxy_buffering off`, `proxy_read_timeout 600s`. In production run it under systemd as its own user, with the
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
