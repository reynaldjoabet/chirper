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

## Running the UI

For UI work, run Vite directly. This is the loop with hot module replacement:

```bash
cd ui
npm install     # first time only (or npm ci)
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
npm run build    # production bundle (sbt does this for you; rarely needed by hand)
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
5. That generator asks for the bundle — line 223 — and `frontendBuild.value` is what forces the build:

```scala
private def frontendResources = Def.task {
  val conv = fileConverter.value
  frontendBuild.value.map(ref => conv.toPath(ref).toFile)   // <- forces the build
}
```

6. `frontendBuild` runs Vite — line 204 — or doesn't. It's a `Def.cachedTask` keyed on the content hashes of frontendSources. Changed → runs `npm run build -- --outDir` … (line 214). Unchanged → restores the bundle from sbt's cache and Vite never starts.

7. The output is already in the right place. `frontendTarget` is `resourceManaged/public` (line 111), so those files are managed resources.

```sh
sbt run → classpath → Compile/products → resources → managedResources
        → resourceGenerators → frontendResources → frontendBuild → vite build
```

```sh
[chirper] $ inspect frontendBuild
[info] Task: Seq[interface xsbti.HashedVirtualFileRef]
[info] Description:
[info]  Builds the UI bundle. Cached on UI source contents.
[info] Provided by:
[info]  frontendBuild
[info] Defined at:
[info]  FrontendPlugin.scala:144
[info] Dependencies:
[info]  state
[info]  frontendBuild / streams
[info]  frontendDirectory
[info]  fileConverter
[info]  frontendSources
[info]  localDigestCacheByteSize
[info]  cacheVersion
[info]  frontendInstall
[info]  frontendBuildCommand
[info]  frontendTarget
[info] Reverse dependencies:
[info]  frontendBuild / dynamicFileOutputs
[info]  Compile / resourceGenerators
[info] Delegates:
[info]  frontendBuild
[info]  ThisBuild / frontendBuild
[info]  Global / frontendBuild
```

`frontendSources` — the content hashes of `ui/`. This is the cache key.

sbt's cache is a content-addressed store: everything is a blob keyed by `sha256-<hash>-<size>`. A single file maps to that model directly, but a directory is a tree of files. sbt computes a hash for the directory by hashing the hashes of its contents, recursively. So if you change one file in `ui/src`, the hash of `ui/` changes, and `frontendBuild` runs again.

`public.sbtdir.zip` is a symlink into `~/Library/Caches/sbt/v2/cas/`. sbt points at the blob rather than copying it. That's also why the whole `resource_managed/main/public` directory reports `0B` from `du` — every file in it is a symlink into the CAS, not a real file.

On a cache hit, sbt unzips it back into place — that's `DiskActionCacheStore.unpackageDirZip`

```sh
[chirper] $ show resourceManaged
[info] /Users/joabet/Desktop/Projects/chirper/target/out/jvm/scala-3.8.4/chirper/resource_managed
[chirper] $ show Compile/resourceManaged
[info] /Users/joabet/Desktop/Projects/chirper/target/out/jvm/scala-3.8.4/chirper/resource_managed/main
[chirper] $ show Test/resourceManaged
[info] /Users/joabet/Desktop/Projects/chirper/target/out/jvm/scala-3.8.4/chirper/resource_managed/test
[chirper] $ 
```