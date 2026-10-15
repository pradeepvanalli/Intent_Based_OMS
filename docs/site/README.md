# Landing page

`index.html` is the static landing page for GitHub Pages. It is a single self-contained file: inline CSS, no
JavaScript, system font stack, light and dark through `prefers-color-scheme`. The architecture diagram is CSS and
inline SVG, not Mermaid, so it renders without a script. Document links point at GitHub `blob/main` URLs so they work
wherever the page is served.

## How it is published

`.github/workflows/pages.yml` (owned by the release tooling, not this folder) uploads **`docs/site/` only** to GitHub
Pages on every push to `main` that touches `docs/site/**`, or by hand with `workflow_dispatch`. One-time setup:
repository Settings, Pages, Source "GitHub Actions". The site is then served at `https://<owner>.github.io/<repo>/`
(or the custom domain in `docs/site/CNAME` if one is added). Nothing is built; the file is served as is.

## Images

The page references screenshots as `images/<name>.png`. `pages.yml` copies `docs/images/*.png` into
`docs/site/images/` as a build step before uploading, so once the files named in `docs/images/README.md` exist there
(hero-playground.png, decision-trace.png, margin-replay.png, copilot-changeset.png, plus any of the recommended
extras), the published page picks them up automatically on the next deploy. A missing file is skipped, not fatal, so
the site still deploys before every shot is captured — it just shows a broken image until that one lands.

To preview locally with images working:

```bash
cd /path/to/intent-oms/docs/site && mkdir -p images && cp ../images/*.png images/ 2>/dev/null; python3 -m http.server 8000
# open http://localhost:8000/
```

## Editing

* Keep it one file. If you need a second page, add it next to this one and link from the footer.
* No external scripts, fonts or stylesheets. The page must render offline.
* Check both colour schemes (`prefers-color-scheme` emulation in the browser devtools) and a 400 px viewport.
* Numbers on the page must exist in the docs (`docs/DEMO_SAMPLES.md`, `README.md`). Do not invent benchmarks.
* The repository URL is wired in (`github.com/pradeepvanalli/Invent_Based_OMS`); `REPLACE_ME` (LinkedIn) still needs
  filling in before launch (`docs/launch/LAUNCH_CHECKLIST.md`).
