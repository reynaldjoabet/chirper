# chirper

A chirp is a short bird sound, users post short messages called chirps.

A Play (Java) backend in `app/`, and a React + Vite UI in `ui/`. `FrontendPlugin` (in
`project/FrontendPlugin.scala`) builds the UI with Vite and puts the bundle on the classpath at
`/public`, where `UIController` serves it. There is no checked-in build output and nothing to
copy by hand: `sbt run`, `sbt stage` and `sbt dist` all build the UI as part of the normal build.

## Requirements

- JDK 21
- sbt 2.x — always invoke it as `sbt --client`
- Node 20+ / npm

## Running the backend

```bash
sbt --client run
```

Play starts on <http://localhost:9000> and serves the UI itself — `sbt run` does **not** start a
Vite server, and there is nothing to run on a second port. The UI bundle is built automatically on
startup (`npm ci` runs first if `node_modules` is missing or `package-lock.json` changed).

One thing to know: **Play serves the bundle as it was at startup.** Editing something in `ui/src`
and refreshing `:9000` will not show the change — Play's dev asset classloader is fixed when `run`
starts. Restart `sbt run` to pick up UI changes, or better, use the UI loop below.

Useful tasks:

```bash
sbt --client frontendBuild    # build the UI bundle only
sbt --client frontendInstall  # npm ci, if the lock file changed
sbt --client stage            # full app + UI -> target/.../universal/stage
sbt --client dist             # zip for deployment
```

## API

All endpoints answer `{"success":true,"data":...}` or `{"success":false,"error":"..."}`. Chirps
persist in an H2 database (a file under `data/`, gitignored) through Play's pooled JDBC API;
evolutions in `conf/evolutions/default/` manage the schema, and the dev database is seeded with
three chirps on first boot only. `request.http` holds runnable examples of all of these.

| Method | Path                    | What it does                                            |
|--------|-------------------------|---------------------------------------------------------|
| GET    | `/api/chirps`           | Timeline, newest first. `?author=handle` filters.       |
| POST   | `/api/chirps`           | `{"author","body"}` → 201. Handle `[A-Za-z0-9_]{1,15}`, body ≤ 280 chars. |
| GET    | `/api/chirps/:id`       | One chirp, or a JSON 404.                               |
| POST   | `/api/chirps/:id/like`  | Increments likes; returns the updated chirp.            |
| DELETE | `/api/chirps/:id`       | 204, or a JSON 404.                                     |

There is no auth: the "current user" is whatever handle the UI has in its compose box. Unknown
`/api/...` paths return a JSON 404 rather than the HTML shell. The 280 limit counts code points,
so an emoji costs 1, not 2 — the UI counter uses the same rule.

## Deployment configuration

Everything deployment-specific is an environment variable; the checked-in defaults only serve
local dev:

| Variable | Purpose |
|---|---|
| `APPLICATION_SECRET` | Overrides the (public) dev secret in application.conf. Set it in any real deployment. |
| `ALLOWED_HOST` | Adds your domain to the Host-header allowlist (dev allows only localhost). |
| `DATABASE_JDBC_URL` | Overrides the dev H2 url. **Required in production**: the dev url is a relative path, and the staged start script resolves it against its own directory, not yours — two launch directories silently mean two databases. Use an absolute H2 path or a Postgres url. |
| `DATABASE_DRIVER` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` | Set alongside `DATABASE_JDBC_URL` when moving off H2 (e.g. `org.postgresql.Driver`). |

Blocking JDBC runs on a dedicated `database.dispatcher` pool sized to match HikariCP's
connections, so slow queries queue there instead of starving request handling.

## Running the UI

For UI work, run Vite directly. This is the loop with hot module replacement:

```bash
cd ui
npm ci          # first time only (npm install also works, but can rewrite the lock file)
npm run dev
```

Vite serves on <http://localhost:5173> with HMR — changes appear immediately, without a rebuild or
a refresh. Open `:5173`, not `:9000`, while doing UI work.

Because the browser is now talking to Vite rather than Play, API calls need forwarding back to the
backend. `ui/vite.config.ts` already does this:

```ts
server: {
  proxy: { '/api': 'http://localhost:9000' },
}
```

So run `sbt --client run` **as well** if you need the API — `:5173` for the UI, and it forwards
`/api/*` to Play on `:9000` behind the scenes. The prefix matches the `/api` route in `conf/routes`;
if you add API routes outside `/api`, add them to the proxy too.

Other UI commands:

```bash
npm run build    # writes to ui/dist, which Play never serves — use `sbt frontendBuild` instead
npm run lint
npm run preview  # serve a production build locally
```


## Notes

- The UI bundle is written to `target/.../resource_managed/main/public`, not to the top-level
  `public/` directory. It lands on the classpath at `/public` from there, so `clean` removes it and
  it never needs a `.gitignore` entry.
- `frontendBuild` is cached on the contents of the UI sources, so an unchanged `ui/` does not rerun
  Vite — even after `clean`.


## How the Frontend Build Integrates
1. `sbt run` needs a classpath. To start Play it has to know what's on the classpath. That means `Compile / products`.
2. The classpath includes resources. `Compile / products` → `Compile / copyResources` → `Compile / resources`, which is unmanaged (`conf/`, top-level `public/`) plus managed resources.
3. Managed resources are whatever the generators produce. `Compile / managedResources` is just the joined output of every task registered in `Compile / resourceGenerators`. sbt has to run those tasks to know what the resources are.
4. The plugin registers one generator — `FrontendPlugin.scala`:

```scala
Compile / resourceGenerators += frontendResources.taskValue
```
5. That generator is `frontendResources`, and its `frontendBuild.value` is what forces the build.
   It does two more things: it converts the cached refs back to the `Seq[File]` that
   `resourceGenerators` expects, and — because it is deliberately uncached and so runs on every
   build — it verifies the bundle files actually exist on disk, restoring any missing ones from
   the cache store (see the caching section below for why that check has to exist).

6. `frontendBuild` runs Vite — or doesn't. It's a `Def.cachedTask` keyed on the content hashes of
   `frontendSources`. Changed → runs `npm run build -- --outDir …` into `frontendTarget`.
   Unchanged → restores the bundle from sbt's cache and Vite never starts.

7. The output is already in the right place. `frontendTarget` is
   `(Compile / resourceManaged) / "public"`, so those files are managed resources.

```sh
sbt run → classpath → Compile/products → resources → managedResources
        → resourceGenerators → frontendResources → frontendBuild → vite build
```

`inspect` confirms the chain from sbt's own dependency graph (output abridged — the full dump
includes line numbers and cache plumbing that drift with edits):

```sh
[chirper] $ inspect frontendBuild
[info] Task: Seq[interface xsbti.HashedVirtualFileRef]
[info] Dependencies:
[info]  frontendSources        <- the cache key
[info]  frontendInstall        <- npm ci gate; runs every build, cache hit or not
[info]  frontendBuildCommand, frontendTarget, frontendDirectory
[info] Reverse dependencies:
[info]  frontendResources      <- the resource generator (next hop)
[info]  frontendBuild / dynamicFileOutputs

[chirper] $ inspect frontendResources
[info] Task: Seq[class java.io.File]
[info] Reverse dependencies:
[info]  Compile / resourceGenerators
```

Note what is absent: `Compile / compile` is nowhere in the reverse chain, which is why
`sbt compile` never builds the UI — only tasks that need resources (run, packageBin, stage, dist,
test) reach it.

`frontendSources` is the cache key. There is no recursive "hash of `ui/`": the `frontendBuild /
fileInputs` globs enumerate specific files (`src/**`, `index.html`, the lock file, vite/tsconfig
config, `.env*`), each file gets its own content hash, and that list of per-file hashes is what the
cache key is built from. Change one byte in one matched file and the key changes; touch a file the
globs don't match (say, `ui/eslint.config.js`) and it doesn't.

After a build, everything under `resource_managed/main/public` — and the sibling
`public.sbtdir.zip` archive — is a symlink into `~/Library/Caches/sbt/v2/cas/`. sbt points at the
blob rather than copying it, which is why the whole directory reports `0B` from `du`.

On a cache hit after `clean`, sbt re-syncs the archive and unzips it back into place
(`DiskActionCacheStore.unpackageDirZip`). But if the archive symlink is already present with the
right digest, sbt trusts the directory contents without validating them — a hand-deleted
`public/` would stay deleted through green builds. `frontendResources` closes that hole: it
re-checks every bundle file on disk on every build and restores missing ones from the CAS.

The `main` in that path comes from the configuration axis: scoping a path key to a config appends
a subdirectory (`Compile` → `main`, `Test` → `test`), which is what keeps configs from writing
over each other. `frontendTarget` uses the `Compile`-scoped value on purpose — a bundle outside
`Compile / resourceManaged` would silently be dropped from the jar, because managed resources are
rebased relative to it:

```sh
[chirper] $ show resourceManaged
[info] /Users/joabet/Desktop/Projects/chirper/target/out/jvm/scala-3.8.4/chirper/resource_managed
[chirper] $ show Compile/resourceManaged
[info] /Users/joabet/Desktop/Projects/chirper/target/out/jvm/scala-3.8.4/chirper/resource_managed/main
[chirper] $ show Test/resourceManaged
[info] /Users/joabet/Desktop/Projects/chirper/target/out/jvm/scala-3.8.4/chirper/resource_managed/test
[chirper] $ 
```

### Why packageBin filters its mappings

When `frontendBuild` declares its output directory, sbt's action cache drops an archive of the
whole bundle *next to* that directory: `resource_managed/main/public.sbtdir.zip`, sitting beside
`public/`. Because it's inside the managed-resource directory, packageBin's scan sweeps it into
the jar — where it would be a complete second copy of every asset. The plugin filters it out of
`Compile / packageBin / mappings`:

```scala
val out = frontendTarget.value.toPath              // …/resource_managed/main/public
val prefix = out.getFileName.toString + "."        // "public."
.filterNot { case (ref, _) =>
  val p = conv.toPath(ref)
  p.getParent == out.getParent &&                  // sits directly beside the bundle dir…
  p.getFileName.toString.startsWith(prefix)        // …and is named "public.<anything>"
}
```

It matches by *location* rather than by the `.sbtdir.zip` extension on purpose: the extension is
an sbt-internal constant (`ActionCache.dirZipExt` is `private[sbt]`, so it can only be copied,
not referenced), and a hardcoded copy fails silently if sbt ever renames it — no error, the jar
just doubles. Only the action cache writes siblings of a declared output directory, so the
location is the stable part.

### What the cache stores

sbt 2 keeps a build cache at `~/Library/Caches/sbt/v2/` — outside your project, shared by every sbt 2 project on your machine, and untouched by `clean` (which only deletes `target/`).  It has two halves:
- `cas/` — the content-addressed store. Every file is named by the `SHA-256` of its own contents plus its size, like `sha256-183bcc…-452`. Nothing here knows what project or filename the content belonged to; it's just bytes filed under their own fingerprint.
- `ac/` — the action cache. Entries here are keyed by a hash of a task's inputs (your UI source hashes + the task's code + the command settings)

So a cache lookup is: hash the inputs → look in `ac/` → follow the pointers into `cas/`.

When `frontendBuild` runs for real, one cache entry gets written, and your UI ends up in the store in three shapes — each existing to answer a different question later:

1. The return value, as JSON. `frontendBuild` returns a list of file references. That list is serialized and stored, so on a hit sbt can hand the downstream tasks their answer without running anything. This is why the whole `import sbt.util.CacheImplicits.given` dance exists at the top of the plugin — the return type had to be serializable to be storable.

2. The whole bundle as one zip (`public.sbtdir.zip`, 85KB). A directory can't have a single content hash — its file names change every build (`index-DfKp6xNp.js` → `index-KZ8fgVzg.js`). So Def.declareOutputDirectory makes sbt zip the directory into one addressable blob. This is the unit of restore-after-clean, and the unit a remote cache would ship to a CI machine that never built the UI.

3. Every individual file as its own blob. When sbt unpacks that zip, `unpackageDirZip` calls `putBlob` on each file it extracts — so `index.html` and each `JS`/`CSS`/`SVG` file get filed in the CAS independently, on top of being inside the zip. sbt does this for its own bookkeeping, but it's what makes `frontendResources`'s self-heal possible: if the bundle directory is deleted by hand, the plugin asks the store for exactly the missing files by their hashes and re-links them — no vite run, no `clean`.