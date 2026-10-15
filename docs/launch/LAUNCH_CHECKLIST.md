# Launch checklist

Work top to bottom. Nothing below "Make the repository public" happens until everything above it is done.

## Before anything else

- [ ] **Rotate the OpenRouter key that was pasted in chat.** Go to openrouter.ai, revoke it, create a new one, put it
      only in `.env` (git-ignored). Then grep the tree for `sk-or-` and for the old key's prefix:
      `grep -rn "sk-or-" /Users/pawanhegde/Documents/OMS --exclude-dir=.git --exclude=.env`. Must return nothing.
- [ ] Check `.env` is in `.gitignore` and was never committed: `git log --all -- .env` must be empty.
- [ ] Run gitleaks locally before the first push (`gitleaks detect --source .`); CI runs it too but the first push is
      what gets indexed.
- [ ] Remove `.DS_Store` files and add `.DS_Store` to `.gitignore`.
- [x] GitHub org/repo decided and wired in: `github.com/pradeepvanalli/Intent_Based_OMS`, image
      `ghcr.io/pradeepvanalli/intent_based_oms` (GHCR requires lowercase; the exact-case repo name is preserved only
      in GitHub URLs). Verify no stray placeholder remains before every future release:
      `grep -rln "intent-oms/intent-oms" . --exclude-dir=.git --exclude-dir=node_modules`.
- [ ] Replace the LinkedIn placeholder (`REPLACE_ME`) in `README.md`, `docs/site/index.html`,
      `docs/launch/PITCH_ONE_PAGER.md` and the posts.
- [ ] Decide the author name for `NOTICE`, `GOVERNANCE.md` and the DCO examples.

## Build and verify

- [ ] Fresh clone into a temp directory; run `scripts/bootstrap.sh`; run `scripts/usecases/run-all.sh` with and
      without `OPENROUTER_API_KEY`. Both must finish.
- [ ] `make try` from a machine without JDK or Node (the GHCR image path). Requires the image to be published first:
      tag `v0.2.0`, let CI push to GHCR, then verify `docker-compose.quickstart.yml` pulls it.
- [ ] Open the repository in Codespaces from the badge; wait for the devcontainer; open port 8080; log in.
- [ ] Run each reference script: `scripts/reference/retail.sh`, `electronics.sh`, `apparel.sh`.
- [ ] `cd backend && mvn -q test` green; `cd ui && npm run lint && npm test -- --run && npm run build` green.
- [ ] Update the test count in `README.md`, `docs/launch/*` and `docs/FAQ.md` if it moved from 570 (verified 2026-10-14: 570 backend tests, 0 failures, plus 73 UI tests).

## Screenshots and video

- [ ] Capture every image listed in `docs/images/README.md` at 1920x1080, light theme, browser zoom 110%. The four
      README placeholders are required: `hero-playground.png`, `decision-trace.png`, `margin-replay.png`,
      `copilot-changeset.png`.
- [ ] Optimise PNGs (`pngquant` or `oxipng`); keep each under 400 KB so the README loads fast.
- [ ] Record the three-minute video from `docs/launch/DEMO_VIDEO_SCRIPT.md`. Export 16:9 master and the 1:1 Playground
      crop. Upload to YouTube unlisted first; make it public on launch day.
- [ ] Add the video link to `README.md` under the hero image and to `docs/site/index.html`.

## Repository settings

- [ ] Make the repository public.
- [ ] Description: "Open-source order management system built on typed, explainable LLM decisions. Java 21, Spring
      Boot, Postgres." Website: the GitHub Pages URL.
- [ ] Topics: `order-management`, `oms`, `ecommerce`, `retail`, `supply-chain`, `llm`, `decision-engine`,
      `spring-boot`, `java`, `postgresql`, `mcp`, `agentic-commerce`, `open-source`.
- [ ] Enable Discussions. Create categories: Announcements, Q&A, Ideas, Show and tell, Reference implementations. Post
      the announcement in Announcements and pin it.
- [ ] Enable GitHub Pages with Source "GitHub Actions" (the `pages.yml` workflow). Verify the page renders in dark and
      light mode, and that the four screenshots show (the workflow copies `docs/images/*.png` into the published
      `images/` folder automatically once those files exist — see `docs/site/README.md`).
- [ ] Labels: `good first issue`, `help wanted`, `reference-implementation`, `decision`, `ui`, `module`, `infra`,
      `security`, `agents`, `analytics`, `core`, `docs`. Colours do not matter; names do, because the roadmap links
      them.
- [ ] Branch protection on `main`: require CI, require one review, no force push. Add the DCO check app.
- [ ] Security policy visible (`SECURITY.md` is picked up automatically); enable private vulnerability reporting.
- [ ] Add the Feedback button target (issue template or form URL) in the app settings; confirm it opens from the
      console header.

## First issues

Create these before launch so the Issues tab is not empty. Label each `good first issue` and link the section of
`CONTRIBUTING.md`.

- [ ] Add a golden-set case for `return_risk` covering a gift return without a receipt.
- [ ] Add `es-US` translations for the customer-safe explanation templates.
- [ ] Store associate app: the pick-list screen only.
- [ ] A `docs/examples/curl` script for the margin replay endpoint.
- [ ] Reference implementation: grocery (perishables and substitutions), scoped per the contribution guide.
- [ ] Carrier connector: one more aggregator behind the existing `CarrierConnector` SPI with a deterministic mock.
- [ ] Hosted demo: hourly reset job and read-mostly roles (label `help wanted`, `infra`).

## Pin and polish

- [ ] Pin the Showcase: set the console to open `/showcase` on first login for the demo tenant, and link it from the
      README "Try it" section.
- [ ] Pin three issues: the hosted demo, the roadmap discussion, and "What vertical should the next reference
      implementation be?".
- [ ] Tag the release `v0.2.0`, write the release notes from `CHANGELOG.md`, attach the video link.
- [ ] Read `README.md` once on a phone.

## LinkedIn timing

- [ ] Publish the blog post the evening before (so the link resolves and the preview card renders).
- [ ] Announcement post: Tuesday or Wednesday, 8:30 to 9:30 am in your audience's main time zone. Attach the 1:1 video
      crop or `hero-playground.png`. Put the repository link in the first comment as well as the post.
- [ ] Reply to every comment within the first two hours using `LINKEDIN_POSTS.md` templates. Ask one question back.
- [ ] Show HN the same morning, 8 to 10 am US Eastern, text from `SHOW_HN.md`; post the first comment immediately.
- [ ] Series posts: one every three to four days for two weeks, in the order written. Reuse the best-performing hook.
- [ ] Day 14: the "who it is for" post plus a Discussions thread asking for order exports to replay.
- [ ] Message, individually, ten people you know who run or build order management. Send the one-pager, not the post.
