#!/usr/bin/env python3
"""Builds the DCrux website (plain HTML and CSS, no dependencies) into a folder that GitHub Pages can serve.

    python3 scripts/site.py            (from the repository's top folder; writes docs/)
    python3 scripts/site.py <repo> <out dir>

The words of the Privacy and Terms pages are taken from the app itself (ui/settings/AboutScreen.kt), so
the site cannot say something the app does not. Pictures live in <out>/shots: d-<name>.jpg from the computer
layout and a-<name>.jpg from the phone layout for home, notes, papers, tests, mains and ask, plus social.jpg.
They are taken from a demo data folder with the off-screen renderer (desktop/.../Render.kt).
"""
import html, os, re, shutil, sys

repo = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else '.')
out = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else os.path.join(repo, 'docs'))
BASE = 'https://thegeekygamechanger.github.io/DCrux/'
GH = 'https://github.com/thegeekygamechanger/DCrux'
TITLE = 'DCrux: the daily crux of current affairs for UPSC and State PSC'
DESC = ('Free, open-source current-affairs app for UPSC and State PSC aspirants on Android, Windows and Linux. '
        'Lectures become notes, headlines are scored for your exam, daily Prelims tests, and Mains answers marked.')

os.makedirs(os.path.join(out, 'fonts'), exist_ok=True)
os.makedirs(os.path.join(out, 'shots'), exist_ok=True)
for f in ('inter_regular.ttf', 'inter_semibold.ttf', 'heros_bold.otf'):
    shutil.copy(os.path.join(repo, 'app/src/main/res/font', f), os.path.join(out, 'fonts', f))
shutil.copy(os.path.join(repo, 'desktop/icons/icon.png'), os.path.join(out, 'icon.png'))

CSS = r'''/* The app's own typefaces, served with the page: Inter for text, a Helvetica (TeX Gyre Heros) for headlines. */
@font-face { font-family:"Inter"; src:url("fonts/inter_regular.ttf") format("truetype"); font-weight:400; font-display:swap; }
@font-face { font-family:"Inter"; src:url("fonts/inter_semibold.ttf") format("truetype"); font-weight:600 700; font-display:swap; }
@font-face { font-family:"Heros"; src:url("fonts/heros_bold.otf") format("opentype"); font-weight:400 900; font-display:swap; }
/* The app's own colours (ui/theme/Theme.kt): its indigo, the periwinkle of its cards, the teal its titles end in. */
:root { --ink:#1c1b20; --soft:#48454e; --line:#e6e0ec; --bg:#fcf8ff; --tint:#f1ecf4; --card:rgba(252,248,255,.74);
  --primary:#3f4bc4; --on-primary:#ffffff; --mid:#565b71; --teal:#006b5f;
  --b1:#2e3a9e; --b2:#4553c9; --b3:#6a78e0; --wash:.17; --glow:.34; }
@media (prefers-color-scheme: dark) { :root { --ink:#e5e1e9; --soft:#cac4cf; --line:#2a292f; --bg:#131218; --tint:#201f25; --card:rgba(32,31,37,.74);
  --primary:#bcc2ff; --on-primary:#1a2478; --mid:#c1c5dc; --teal:#6fdbc9; --wash:.20; --glow:.30; } }
* { box-sizing:border-box; }
html { -webkit-text-size-adjust:100%; scroll-behavior:smooth; }
body { margin:0; background:var(--bg); color:var(--ink); font:17px/1.6 "Inter", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; overflow-x:hidden; }
h1, h2, h3 { font-family:"Heros", Helvetica, "Helvetica Neue", Arial, sans-serif; font-weight:700; letter-spacing:-0.02em; line-height:1.08; margin:0; }
a { color:inherit; }
.wrap { max-width:1080px; margin:0 auto; padding:0 24px; }

/* The gradient is the app's: its indigo washing down from the top into a trace of teal, as behind every
   screen, with the periwinkle of its cards drifting slowly through it. */
.dawn { position:absolute; z-index:-1; inset:0 0 auto 0; height:920px; overflow:hidden; pointer-events:none;
  background:linear-gradient(color-mix(in srgb, var(--primary) calc(var(--wash) * 100%), transparent), color-mix(in srgb, var(--teal) 7%, transparent) 55%, transparent);
  -webkit-mask-image:linear-gradient(#000 40%, transparent); mask-image:linear-gradient(#000 40%, transparent); }
.dawn::before { content:""; position:absolute; inset:-30% -60%; opacity:var(--glow); filter:blur(80px);
  background:
    radial-gradient(36% 44% at 24% 30%, var(--b3), transparent 70%),
    radial-gradient(32% 42% at 50% 18%, var(--b2), transparent 70%),
    radial-gradient(30% 40% at 78% 30%, var(--teal), transparent 72%);
  animation:drift 32s ease-in-out infinite alternate; will-change:transform; }
@keyframes drift { from { transform:translate3d(-7%, 0, 0) rotate(-2deg); } to { transform:translate3d(7%, 3%, 0) rotate(2deg); } }
@media (prefers-reduced-motion: reduce) { .dawn::before { animation:none; } }
/* The headline runs through the same three colours as the titles inside the app. */
.grad { background:linear-gradient(100deg, var(--primary), var(--mid) 55%, var(--teal)); -webkit-background-clip:text; background-clip:text; color:transparent; }

header { display:flex; align-items:center; justify-content:space-between; padding:20px 0; }
.mark { display:flex; align-items:center; gap:10px; font-weight:600; font-size:18px; text-decoration:none; }
.mark img { width:32px; height:32px; }
nav { display:flex; align-items:center; }
nav a { margin-left:22px; color:var(--soft); text-decoration:none; font-size:15px; }
nav a:hover { color:var(--ink); }
nav a.pill { color:var(--on-primary); background:var(--primary); padding:8px 16px; border-radius:999px; font-weight:600; }

.hero { padding:76px 0 40px; text-align:center; }
.eyebrow { display:inline-block; font-size:14px; font-weight:600; letter-spacing:.08em; text-transform:uppercase; color:var(--soft); margin-bottom:18px; }
.hero h1 { font-size:clamp(42px, 8vw, 82px); }
.hero p { font-size:20px; color:var(--soft); max-width:31em; margin:22px auto 0; }
.buttons { display:flex; flex-wrap:wrap; gap:12px; justify-content:center; margin-top:30px; }
.btn { display:inline-block; padding:13px 24px; border-radius:999px; border:1px solid var(--primary); color:var(--primary); text-decoration:none; font-weight:600; font-size:16px; transition:transform .15s ease; }
.btn.primary { background:var(--primary); color:var(--on-primary); }
.btn:hover { transform:translateY(-2px); }
.small { font-size:14px; color:var(--soft); margin-top:14px; }

/* The Desktop / Android switch: one highlight that slides between the two. */
.switch { position:relative; display:inline-grid; grid-template-columns:1fr 1fr; margin:38px auto 22px; padding:4px; background:var(--card); border:1px solid var(--line); border-radius:999px; backdrop-filter:blur(10px); }
.switch button { position:relative; z-index:1; border:0; background:none; font:inherit; font-weight:600; font-size:15px; color:var(--soft); padding:9px 26px; border-radius:999px; cursor:pointer; transition:color .25s ease; }
.switch button[aria-selected="true"] { color:var(--on-primary); }
.switch .thumb { position:absolute; top:4px; bottom:4px; left:4px; width:calc(50% - 4px); background:var(--primary); border-radius:999px; transition:transform .35s cubic-bezier(.3,1.4,.5,1); }
body[data-device="android"] .switch .thumb { transform:translateX(100%); }

.shot { margin:0; display:flex; justify-content:center; }
.shot img { display:block; max-width:100%; height:auto; border:1px solid var(--line); box-shadow:0 30px 80px -30px rgba(46,58,158,.42); background:var(--tint); }
.shot img.desktop { border-radius:16px; width:100%; }
.shot img.android { border-radius:34px; width:min(300px, 78vw); }
body[data-device="desktop"] .android, body[data-device="android"] .desktop { display:none; }

.feature { display:grid; grid-template-columns:1fr 1.5fr; gap:48px; align-items:center; padding:64px 0; border-top:1px solid var(--line); }
.feature.flip > div { order:2; }
.feature.flip { grid-template-columns:1.5fr 1fr; }
.feature .n { font-size:14px; font-weight:600; letter-spacing:.08em; color:var(--primary); margin-bottom:12px; }
.feature h2 { font-size:clamp(30px, 4.4vw, 44px); margin-bottom:14px; }
.feature p { color:var(--soft); font-size:18px; margin:0; max-width:23em; }
body[data-device="android"] .feature { grid-template-columns:1.2fr 1fr; }
body[data-device="android"] .feature.flip { grid-template-columns:1fr 1.2fr; }

.why { padding:64px 0 8px; border-top:1px solid var(--line); text-align:center; }
.why h2 { font-size:clamp(30px, 4.4vw, 44px); }
.why > p { color:var(--soft); font-size:18px; max-width:30em; margin:14px auto 0; }
.grid { display:grid; grid-template-columns:repeat(3, 1fr); gap:1px; background:var(--line); border:1px solid var(--line); border-radius:22px; overflow:hidden; margin:36px 0 72px; text-align:left; }
.grid div { background:var(--tint); padding:26px; }
.grid h3 { font-size:19px; margin-bottom:8px; }
.grid p { margin:0; color:var(--soft); font-size:15.5px; }

.get { margin:0 0 72px; padding:44px 28px; border-radius:28px; text-align:center; color:#fff;
  background:linear-gradient(115deg, var(--b1), var(--b2) 55%, var(--b3)); }
.get h2 { font-size:clamp(28px, 4vw, 40px); }
.get p { margin:12px auto 0; max-width:30em; opacity:.92; }
.get .btn { border-color:#fff; color:#fff; }
.get .btn.primary { background:#fff; color:#2e3a9e; }

footer { border-top:1px solid var(--line); padding:28px 0 44px; color:var(--soft); font-size:14.5px; }
footer a { margin-right:18px; }
footer p { margin:12px 0 0; }
article { padding:48px 0 80px; max-width:720px; }
article h1 { font-size:44px; margin-bottom:26px; }
article h2 { font-size:23px; margin:34px 0 8px; }
article p, article li { color:var(--soft); }
article p { margin:0 0 12px; }

@media (max-width: 820px) {
  .feature, .feature.flip, body[data-device="android"] .feature, body[data-device="android"] .feature.flip { grid-template-columns:1fr; gap:26px; padding:44px 0; text-align:center; }
  .feature.flip > div { order:0; }
  .feature p { margin:0 auto; }
  .grid { grid-template-columns:1fr 1fr; }
  nav a:not(.pill) { display:none; }
  .hero { padding-top:44px; }
}
@media (max-width: 520px) { .grid { grid-template-columns:1fr; } }
button, input, select, textarea { font-family:inherit; }
'''

def head(title, desc, path):
    url = BASE + path
    return f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<meta name="description" content="{html.escape(desc)}">
<link rel="canonical" href="{url}">
<meta property="og:type" content="website">
<meta property="og:site_name" content="DCrux">
<meta property="og:title" content="{html.escape(title)}">
<meta property="og:description" content="{html.escape(desc)}">
<meta property="og:url" content="{url}">
<meta property="og:image" content="{BASE}shots/social.jpg">
<meta name="twitter:card" content="summary_large_image">
<link rel="icon" href="icon.png">
<link rel="stylesheet" href="style.css">
</head>
'''

NAV = f'''<header>
  <a class="mark" href="index.html"><img src="icon.png" alt="">DCrux</a>
  <nav><a href="privacy.html">Privacy</a><a href="terms.html">Terms</a><a href="{GH}">Source</a><a class="pill" href="{GH}/releases/latest">Download</a></nav>
</header>'''
FOOT = f'''<footer>
  <a href="privacy.html">Privacy</a><a href="terms.html">Terms</a><a href="{GH}">GitHub</a><a href="{GH}/releases/latest">Download</a>
  <p>DCrux is free, open-source software (Apache 2.0). It is not affiliated with the UPSC, any Public Service Commission, YouTube, the channels you follow, or any newspaper. Their names belong to their owners.</p>
</footer>'''

def shot(name, alt):
    return (f'<figure class="shot"><img class="desktop" src="shots/d-{name}.jpg" alt="{alt}, on a computer" loading="lazy">'
            f'<img class="android" src="shots/a-{name}.jpg" alt="{alt}, on a phone" loading="lazy"></figure>')

# The page. Every claim here is something the app does today.
FEATURES = [
    ('notes', 'Lectures, as notes', 'Follow any YouTube channel. Each video becomes study notes you can read in minutes, short or detailed, written only from what the lecture said.', 'A video turned into notes'),
    ('papers', 'The papers, scored for your exam', 'The Hindu, The Indian Express, PIB and sources you add. Every headline is rated for UPSC or your State PSC, with one line on why it matters.', 'The day\'s papers with exam relevance'),
    ('tests', 'Prelims, every day', 'Multiple-choice tests written from the day\'s news, at the difficulty you choose. Retake one to beat your own score.', 'A Prelims test'),
    ('mains', 'Mains, marked like a mentor', 'Write on paper against the clock and photograph the pages. Your handwriting is read, marked on five criteria, and remarked page by page.', 'Mains answer writing'),
    ('ask', 'Ask Crux AI', 'Stuck on a note or an article? Ask there and then. Answers come from the material first, and from the web only when it is not there.', 'Asking Crux AI about a note'),
]
GRID = [
    ('Every device', 'Android, Windows and Linux. Pair two devices by typing a 4-character code and they stay in step within a second.'),
    ('Private by design', 'No account, no ads, no tracking. Synced data is encrypted on your device, and the service that relays it keeps nothing on disk.'),
    ('Search that understands', 'Type words in any order, or a date like 14 nov, and find the video, note or headline you mean.'),
    ('Mind Flow', 'Fifteen soundscapes, from rain to binaural tones, made on your device for long study hours.'),
    ('Weather and real AQI', 'Your city\'s weather, and its air quality from India\'s official CPCB monitors.'),
    ('Free and open source', 'No subscription and no key to buy. Read the code, build it yourself, change it.'),
]

index = head(TITLE, DESC, '') + '''<body data-device="desktop">
<div class="dawn"></div>
<div class="wrap">
''' + NAV + '''
<section class="hero">
  <span class="eyebrow">For UPSC and State PSC aspirants</span>
  <h1>The day's crux,<br><span class="grad">before the exam asks.</span></h1>
  <p>Lectures become notes, headlines are scored for your exam, and your answers get marked. Free, on your phone and your computer.</p>
  <div class="switch" role="tablist" aria-label="Show the app on">
    <span class="thumb"></span>
    <button role="tab" data-show="desktop">Desktop</button>
    <button role="tab" data-show="android">Android</button>
  </div>
  ''' + shot('home', 'The Crux screen') + '''
</section>
''' + '\n'.join(
    f'''<section class="feature{' flip' if i % 2 else ''}">
  <div><div class="n">{i + 1:02d}</div><h2>{html.escape(t)}</h2><p>{html.escape(p)}</p></div>
  {shot(n, html.escape(alt))}
</section>''' for i, (n, t, p, alt) in enumerate(FEATURES)) + '''
<section class="why">
  <h2>Built for the long haul</h2>
  <p>Everything else a serious aspirant needs, and nothing that gets in the way.</p>
  <div class="grid">
''' + '\n'.join(f'    <div><h3>{html.escape(t)}</h3><p>{html.escape(p)}</p></div>' for t, p in GRID) + f'''
  </div>
</section>
<section class="get">
  <h2>Start with today's crux</h2>
  <p>Download once. After that the app keeps itself up to date.</p>
  <div class="buttons"><a class="btn primary" href="{GH}/releases/latest">Download for Android, Windows or Linux</a><a class="btn" href="{GH}">See the source</a></div>
</section>
''' + FOOT + '''
</div>
<script>
// Shows the computer or the phone pictures. The choice is remembered; a narrow screen starts on the phone.
(function () {
  var saved = null;
  try { saved = localStorage.getItem('device'); } catch (e) {}
  function show(device) {
    document.body.dataset.device = device;
    document.querySelectorAll('.switch button').forEach(function (b) { b.setAttribute('aria-selected', b.dataset.show === device); });
    try { localStorage.setItem('device', device); } catch (e) {}
  }
  document.querySelectorAll('.switch button').forEach(function (b) { b.addEventListener('click', function () { show(b.dataset.show); }); });
  // A link can ask for one or the other: index.html?device=android
  var asked = null;
  try { asked = new URLSearchParams(location.search).get('device'); } catch (e) {}
  if (asked !== 'android' && asked !== 'desktop') asked = null;
  show(asked || saved || (window.matchMedia('(max-width: 700px)').matches ? 'android' : 'desktop'));
})();
</script>
</body>
</html>
'''

# Privacy and Terms, word for word from the app.
about = open(os.path.join(repo, 'app/src/main/java/app/dcrux/ui/settings/AboutScreen.kt'), encoding='utf-8').read()
def paras(marker, until):
    block = about[about.index(marker):]
    block = block[:block.index(until)]
    found = re.findall(r'SheetPara\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)\)', block)
    unesc = lambda t: t.replace('\\"', '"').replace('\\n', '\n').replace("\\'", "'").replace('\\\\', '\\')
    return [(unesc(h), unesc(''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', b)))) for h, b in found]

def page(name, title, items):
    body = ''.join((f'<h2>{html.escape(h)}</h2>' if h.strip() else '') + ''.join(f'<p>{html.escape(x)}</p>' for x in b.split('\n') if x.strip()) for h, b in items)
    return head(f'{title} · DCrux', f'DCrux: {title.lower()}.', name) + f'<body>\n<div class="dawn"></div>\n<div class="wrap">\n{NAV}\n<article><h1>{title}</h1>{body}</article>\n{FOOT}\n</div>\n</body>\n</html>\n'

files = {
    'index.html': index, 'style.css': CSS,
    'privacy.html': page('privacy.html', 'Privacy', paras('Info.PRIVACY ->', 'Info.TERMS ->')),
    'terms.html': page('terms.html', 'Terms of use', paras('Info.TERMS ->', 'Info.LICENCES ->')),
    'robots.txt': f'User-agent: *\nAllow: /\nSitemap: {BASE}sitemap.xml\n',
    'sitemap.xml': '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n' + ''.join(f'  <url><loc>{BASE}{p}</loc></url>\n' for p in ('', 'privacy.html', 'terms.html')) + '</urlset>\n',
    '.nojekyll': '',
}
for name, text in files.items():
    open(os.path.join(out, name), 'w', encoding='utf-8').write(text)
want = [f'{d}-{n}.jpg' for n in ['home'] + [f[0] for f in FEATURES] for d in ('d', 'a')] + ['social.jpg']
missing = [w for w in want if not os.path.exists(os.path.join(out, 'shots', w))]
print('built', out, '| pages:', ', '.join(files), '| pictures missing:', ', '.join(missing) or 'none')
