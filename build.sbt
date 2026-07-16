import Dependencies._

ThisBuild / name := """chirper"""
ThisBuild / organization := "com.chirper"
ThisBuild / scalaVersion := "3.8.4"
ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalacOptions ++= Seq(
  "-no-indent",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-explain", // + actionable error messages
  "-source:3.3", // + pin source level, no silent drift
  // "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-Wsafe-init",
  "-language:strictEquality", // + catch nonsensical == (Money vs String, etc.)
  "-Xkind-projector",
  "-Xmax-inlines",
  "64"
)

// Reload the build automatically when build.sbt or project/*.scala changes, rather than printing
// a warning and quietly running against the stale definition until someone types `reload`.
Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val root = (project in file("."))
  // PlayLayoutPlugin comes along automatically and is what puts sources in app/,
  // config in conf/ and static files in public/.
  // FrontendPlugin builds ui/ with Vite onto the classpath at /public; it declares noTrigger, so
  // this is the only thing that turns it on.
  .enablePlugins(PlayJava, FrontendPlugin)
  .settings(
    name := "chirper",

    // The reverse routers exist to build URLs from Twirl/Scala, and the JS one to feed Play's
    // jsRoutes. A Vite-built React SPA can use neither: it knows its own URLs, and its bundle is
    // never served by Twirl. Off, they stop generating and compiling ReverseUIController and
    // routes$javascript on every routes change. The forward router (router.Routes) is unaffected.
    generateReverseRouter := false,
    generateJsReverseRouter := false,

    // Play writes a RUNNING_PID on startup and refuses to boot if one already exists. In a
    // container that file survives an unclean stop, so the next start fails with a stale pid --
    // the classic "works once, then never restarts" deployment failure.
    Universal / javaOptions += "-Dpidfile.path=/dev/null",
    libraryDependencies ++= Seq(
      guice,
      "jakarta.inject" % "jakarta.inject-api" % "2.0.1",
      "com.outr" %% "scribe" % "3.19.0",
      "com.outr" %% "scribe-slf4j" % "3.19.0",
      munit % Test
    )
  )
