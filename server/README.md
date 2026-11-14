# DCrux service

A small relay that keeps the Claude API key on a server, so the public app never carries one.

An app cannot keep a secret: anything inside an APK can be pulled out, however it is encrypted.
So the public build of DCrux holds no key. It sends its two kinds of AI request to this relay,
and the relay adds the key and forwards them.

## What it allows

- `GET /v1/models` and `POST /v1/messages` only, streamed back as they arrive.
- Only the models in `DCRUX_MODELS`, no tools, and a cap on `max_tokens` and request size.
- Only requests signed by the app (install id + time + body hash, HMAC with `DCRUX_APP_SECRET`).
- Daily limits per install, per network address and overall. When a limit is hit the app shows
  the relay's message ("...resets tomorrow").

The signing secret is inside the app, so a determined person can extract it. It stops casual
misuse; **the daily limits are what protect the bill**. Set them to what you are willing to pay for.

Nothing about a request's text is logged or stored: the log has time, path, status, duration,
a hash of the install id, the model and the request size.

## Settings (environment)

| Variable | Default | Meaning |
| --- | --- | --- |
| `ANTHROPIC_API_KEY` | required | The key the relay calls the API with |
| `DCRUX_APP_SECRET` | required | Shared with the app build (`secrets.properties`) |
| `PORT`, `HOST` | `8787`, `127.0.0.1` | Where it listens; put nginx or another TLS front in front |
| `DCRUX_MODELS` | opus-5-5, sonnet-5-5, haiku-5-5 | Comma-separated allow-list |
| `DCRUX_DEVICE_DAILY` | `60` | Requests per install per day |
| `DCRUX_ADDRESS_DAILY` | `150` | Requests per network address per day |
| `DCRUX_GLOBAL_DAILY` | `2000` | Requests per day in total |
| `DCRUX_MAX_TOKENS` | `32000` | Ceiling on `max_tokens` |
| `DCRUX_MAX_BODY_BYTES` | `3000000` | Largest request accepted |
| `DCRUX_STATE_FILE` | none | Where to keep the day's counters across restarts |

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
