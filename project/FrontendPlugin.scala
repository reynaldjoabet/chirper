import scala.sys.process.{Process, ProcessLogger}

import sbt.*
import sbt.Keys.*
import sbt.io.syntax.*
import sbt.nio.Keys.*
import sbt.nio.file.*
// Supplies JsonFormat[HashedVirtualFileRef] (via IsoString) so frontendBuild's result is a
// serializable — and therefore cacheable — task output.
import sbt.util.CacheImplicits.given
import xsbti.HashedVirtualFileRef

/** Builds the React UI (Vite) and makes it part of the Play application.
  *
  * The bundle is written into `Compile / resourceManaged / "public"` rather
  * than a checked-in `public/` folder. That single choice does most of the
  * work: the output lands on the classpath as `/public`, which is where
  * `app.controllers.UIController` reads it from via
  * `assets.at("/public", ...)`; anything consuming `Compile / resources`
  * (`package`, `dist`, `stage`, docker) picks it up without each being patched
  * individually; `clean` removes it; and it never needs a .gitignore entry. It
  * also keeps the output under `target/out`, which sbt 2 requires of a cached
  * task's outputs -- see `frontendBuild`.
  *
  * This plugin builds the bundle and stops there. It deliberately does not run
  * the Vite dev server: `sbt run` starts Play and nothing else, and Play serves
  * the bundle from the classpath on its own port, exactly as it does in
  * production.
  *
  * Note what that does *not* give you: a live UI loop. `run` builds the bundle
  * at startup and Play then serves that snapshot for the lifetime of the
  * process. Editing a component and refreshing does not show the change --
  * Play's dev asset classloader is fixed when `run` starts, so even after its
  * reloader reruns this task and a fresh bundle is on disk, the old one keeps
  * being served. Restarting `run` picks it up. For UI work, run `npm run dev`
  * instead: HMR on its own port, with vite.config.ts proxying /api back to
  * Play. README.md documents both loops.
  *
  * The reason for building it here at all is that dev and prod then serve the
  * same bytes down the same code path. When the dev server fronted the app,
  * Play's `Assets` never ran in dev, so an asset-caching bug in `UIController`
  * stayed invisible until a production build was staged -- dev reported
  * `no-cache` for everything and looked fine.
  *
  * It also means sbt owns no child process. An earlier version started the dev
  * server from a PlayRunHook, which meant reaping an npm -> node process tree
  * on every exit path; a stale watcher outliving `sbt run` holds its port and
  * file watches. Not starting it makes that unreachable rather than handled.
  *
  * The related trap, should anyone reintroduce a watcher: nothing may write
  * into `frontendTarget` except `frontendBuild`. That directory is a declared
  * cached output, and sbt's action cache assumes a declared output directory
  * has exactly one writer -- on a hit it restores only *missing* files and
  * otherwise trusts what is on disk. A `vite build --watch` pointed there
  * leaves development bundles sitting where `dist` will ship them as the
  * production artifact, with a green build.
  */
object FrontendPlugin extends AutoPlugin {

  // Enable explicitly, per project: `.enablePlugins(PlayJava, FrontendPlugin)`.
  override def trigger = noTrigger

  // Play defines the Compile config and the resourceManaged/Assets wiring this builds on top of,
  // and must contribute its settings first.
  override def requires = play.sbt.PlayWeb

  object autoImport {

    val frontendDirectory =
      settingKey[File]("Directory holding the React app's package.json.")

    val frontendTarget =
      settingKey[File](
        "Directory the bundle is written to; served from the classpath as /public."
      )

    val frontendInstallCommand =
      settingKey[Seq[String]](
        "Command that installs node dependencies reproducibly."
      )

    /** Command that produces the production bundle. `frontendBuild` appends
      * `--outDir <dir> --emptyOutDir`, so the command must end in a position
      * where those reach the bundler.
      *
      * For an `npm run` script that means a trailing `--`: without it npm
      * swallows `--outDir` as its own config ("Unknown cli config") and
      * forwards the bare path to the script, where vite reads it as its
      * positional `root` and fails to resolve an entry module.
      */
    val frontendBuildCommand =
      settingKey[Seq[String]](
        "Command that produces the production bundle, minus --outDir."
      )

    val frontendInstall =
      taskKey[Unit](
        "Installs node dependencies when package-lock.json changes."
      )

    val frontendSources =
      taskKey[Seq[HashedVirtualFileRef]](
        "UI sources, content-hashed to key the build cache."
      )

    val frontendBuild =
      taskKey[Seq[HashedVirtualFileRef]](
        "Builds the UI bundle. Cached on UI source contents."
      )
  }

  import autoImport.*

  private val frontendResources =
    taskKey[Seq[File]](
      "Verifies the cached UI bundle exists on disk and adapts it for resourceGenerators."
    )

  private val isWindows = sys.props("os.name").toLowerCase.contains("win")

  // ProcessBuilder resolves npm.cmd on Windows; plain "npm" finds only the shell script there.
  private val npm = if (isWindows) "npm.cmd" else "npm"

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    frontendDirectory := (ThisProject / baseDirectory).value / "ui",
    frontendTarget := (Compile / resourceManaged).value / "public",
    frontendInstallCommand := Seq(npm, "ci"),
    // The trailing `--` is load-bearing; see frontendBuildCommand.
    frontendBuildCommand := Seq(npm, "run", "build", "--"),

    // Declare the UI sources as globs rather than walking the tree by hand. sbt stamps exactly
    // these paths, and the same declaration powers `~frontendBuild` continuous execution for free.
    // They are enumerated rather than globbing all of `ui/**` on purpose: a recursive glob would
    // descend into node_modules and stat six figures' worth of files on every build.
    frontendBuild / fileInputs := {
      val ui = frontendDirectory.value.toGlob
      Seq(
        ui / "src" / ** / "*",
        ui / "public" / ** / "*",
        ui / "index.html",
        ui / "package.json",
        ui / "package-lock.json",
        ui / "vite.config.*",
        ui / "tsconfig*.json",
        // Vite inlines VITE_* values from .env files into the bundle at build time, so these
        // change the output as surely as source does and must invalidate the cache. Listed even
        // though the project has no .env today: vite reads them with no dependency to install, so
        // one can appear without any other signal that this list needs updating.
        ui / ".env*"
      )
    },

    // uncached: this reports the *current* state of the filesystem, so a cached answer would be a
    // stale one. It is the input that gives frontendBuild its cache key, and must stay honest.
    frontendSources := Def.uncached(sourcesTask.value),

    // uncached: node_modules lives outside target/out, so sbt cannot track it as an output, and a
    // cache hit would happily skip `npm ci` on a tree someone had deleted node_modules from.
    frontendInstall := Def.uncached(installTask.value),
    frontendBuild := buildTask.value,

    // uncached: this is where "the bundle exists on disk" is enforced, and an enforcement task
    // that can be skipped as a cache hit enforces nothing -- on a hit, sbt skips exactly the body
    // that does the checking (observed: a hand-deleted frontendTarget shipped as a jar with no UI
    // in it, green). Def.uncached also permits the Seq[File] return type, which a cached sbt 2
    // task rejects.
    frontendResources := Def.uncached(resourcesTask.value),
    Compile / resourceGenerators += frontendResources.taskValue,

    // The action cache archives a declared output directory to a *sibling* of that directory
    // (`public.sbtdir.zip` today), so ours lands inside resourceManaged, where packageBin's
    // mappings scan picks it up. The archive is a complete copy of the bundle, so without this
    // the jar carries every asset twice.
    //
    // frontendTarget has to live under resourceManaged: that is what puts the bundle on the
    // classpath at /public, where both Assets and packaging read it from, and managed resources are
    // rebased relative to resourceManaged. The archive landing there too is collateral. sbt has no
    // exclusion of its own because its cached directories (semanticdb's targetRoot, the classes
    // dir) are never inside a resource directory.
    //
    // Filtered by location -- any sibling named `<target>.<anything>` -- rather than by the
    // `.sbtdir.zip` extension. The extension is sbt-internal (ActionCache.dirZipExt is
    // private[sbt], so it cannot be referenced, only copied), and a hardcoded copy fails silently
    // if sbt ever renames it: no error, the jar just grows by the size of the bundle. Location
    // doesn't rot the same way, because only the action cache writes siblings of a declared
    // output directory.
    Compile / packageBin / mappings := {
      val conv = fileConverter.value
      val out = frontendTarget.value.toPath
      val prefix = out.getFileName.toString + "."
      (Compile / packageBin / mappings).value.filterNot { case (ref, _) =>
        val p = conv.toPath(ref)
        p.getParent == out.getParent && p.getFileName.toString.startsWith(
          prefix
        )
      }
    }
  )

  private def sourcesTask = Def.task {
    val conv = fileConverter.value
    (frontendBuild / allInputFiles).value.map(p =>
      (conv.toVirtualFile(p): HashedVirtualFileRef)
    )
  }

  private def installTask = Def.task {
    val log = streams.value.log
    val dir = frontendDirectory.value
    val modules = dir / "node_modules"
    val lock = dir / "package-lock.json"

    if (!(dir / "package.json").exists())
      sys.error(
        s"No package.json in $dir. Set frontendDirectory to your React app's root."
      )

    // node_modules existing is not enough: it can predate a lock change. Key the install on the
    // lock file's contents so a rebase or a dependency bump forces a fresh `npm ci`.
    //
    // The stamp lives inside node_modules, not under target/, so that it describes exactly the
    // tree it is stamping: `npm ci` wipes node_modules and takes the stamp with it, and `clean`
    // leaves both alone rather than forcing a ~20s reinstall of a tree that already matches.
    // A hash rather than the lock's contents: the stamp is only ever compared for equality, and
    // storing it verbatim left a byte-for-byte 95KB copy of package-lock.json in node_modules.
    val stamp = modules / ".sbt-frontend-install.stamp"
    val current =
      if (lock.exists()) sbt.io.Hash.toHex(sbt.io.Hash(lock)) else ""

    if (
      !modules.isDirectory() || !stamp.exists() || IO.read(stamp) != current
    ) {
      log.info(s"[frontend] Installing node dependencies in $dir")
      run(frontendInstallCommand.value, dir, log, "npm ci failed")
      IO.write(stamp, current)
    }
  }

  /** Cached on the content hashes of `frontendSources`, so an unchanged UI
    * never reruns vite.
    *
    * `declareOutputDirectory` hands the whole bundle to sbt's action cache: it
    * is zipped into the CAS with a manifest of per-file hashes and restored by
    * `syncBlobs` on a hit. That survives `clean`, and with a remote cache
    * configured a CI machine can restore a bundle it never built. A hand-rolled
    * stamp file can do neither -- it can only decide not to redo work whose
    * output still happens to be sitting on this disk.
    */
  private def buildTask = Def.cachedTask {
    val log = streams.value.log
    val dir = frontendDirectory.value
    val out = frontendTarget.value
    val conv = fileConverter.value
    frontendInstall.value // the build needs node_modules present

    // Reading these hashes is what pulls them into this task's cache key.
    val sources = frontendSources.value
    if (sources.isEmpty)
      sys.error(
        s"No UI sources found under $dir. Check frontendDirectory and the " +
          "frontendBuild / fileInputs globs."
      )
    log.info(
      s"[frontend] Building UI bundle from ${sources.size} sources into $out"
    )
    // Emptied here, not left to vite's --emptyOutDir, so it holds for any frontendBuildCommand:
    // after the command runs, everything in frontendTarget was written by this build -- which is
    // what makes the index.html check below meaningful. Without this, output surviving from a
    // previous build satisfies the check and masks a command that silently wrote elsewhere.
    IO.delete(out)
    run(
      frontendBuildCommand.value ++ outDirArgs(out),
      dir,
      log,
      "UI build failed"
    )

    // A build that exits 0 but leaves frontendTarget without an entry page wrote its output
    // somewhere else (typically vite's default ui/dist, when a custom frontendBuildCommand
    // doesn't forward --outDir). Without this check that ships as a jar with no UI in it and a
    // green build; an sbt task that returns Seq.empty fails nothing downstream.
    if (!(out / "index.html").isFile())
      sys.error(
        s"UI build exited 0 but wrote no index.html into $out. " +
          "frontendBuildCommand must let the appended --outDir reach vite; " +
          "an `npm run` script needs a trailing `--`."
      )

    Def.declareOutputDirectory(conv.toVirtualFile(out.toPath))
    (out ** AllPassFilter).get().filter(_.isFile).map { f =>
      (conv.toVirtualFile(f.toPath): HashedVirtualFileRef)
    }
  }

  /** Adapts the cached bundle back to the `Seq[File]` that resourceGenerators
    * still expects.
    *
    * The existence check is not paranoia. On a cache hit sbt only re-syncs the
    * bundle's `.sbtdir.zip` archive, and `syncFile` returns without unpacking
    * when that archive is already in place with a matching digest -- the
    * *contents* of the directory are never validated (ActionCacheStore.scala,
    * `afterFileWrite` only fires on a rewrite). So a hand-deleted
    * frontendTarget stays deleted through any number of green builds, and
    * without this check `packageBin` ships a jar with no UI in it (observed,
    * not theorized).
    *
    * Recovery is cheap because `unpackageDirZip` runs `putBlob` on every file
    * it extracts: each bundle file sits individually in the CAS, so missing
    * files are restored with `syncBlobs` -- no vite run, no `clean`. The error
    * remains only for the case where the CAS itself no longer has the blobs.
    */
  private def resourcesTask = Def.task {
    val log = streams.value.log
    val conv = fileConverter.value
    val cacheConfig = Def.cacheConfiguration.value
    val refs = frontendBuild.value
    val missing = refs.filterNot(ref => conv.toPath(ref).toFile.exists())
    if (missing.nonEmpty) {
      log.info(
        s"[frontend] ${missing.size} bundle files missing from disk; restoring from cache"
      )
      val restored =
        cacheConfig.store.syncBlobs(missing, cacheConfig.outputDirectory)
      if (restored.size != missing.size)
        sys.error(
          s"UI bundle is cached but ${missing.size - restored.size} files are " +
            "missing from both disk and the cache store. frontendTarget was " +
            "modified outside the build; run `clean` to rebuild it."
        )
    }
    refs.map(ref => conv.toPath(ref).toFile)
  }

  /** Vite will not empty an outDir outside its own project root unless told to,
    * and it must be emptied so bundles dropped by a previous build do not
    * linger on the classpath.
    */
  private def outDirArgs(out: File): Seq[String] =
    Seq("--outDir", out.getAbsolutePath, "--emptyOutDir")

  /** Runs a command to completion, failing the build on a non-zero exit.
    *
    * stderr maps to warn, not error: npm writes notices and progress there on
    * perfectly successful runs, and error-level lines in a green build train
    * people to ignore error-level lines. Failure is signalled by the exit code,
    * which fails the task with the command attached.
    */
  private def run(
      cmd: Seq[String],
      cwd: File,
      log: Logger,
      onFailure: String
  ): Unit = {
    log.debug(s"[frontend] ${cmd.mkString(" ")} (in $cwd)")
    val code =
      try Process(cmd, cwd) ! ProcessLogger(log.info(_), log.warn(_))
      catch {
        // Surfaced when the binary itself is absent; the raw exception names a
        // ProcessBuilder, not the actual problem.
        case e: java.io.IOException =>
          sys.error(
            s"Could not start '${cmd.head}' -- is it installed and on the " +
              s"PATH sbt was launched with? (${e.getMessage})"
          )
      }
    if (code != 0) sys.error(s"$onFailure (exit $code): ${cmd.mkString(" ")}")
  }

}
