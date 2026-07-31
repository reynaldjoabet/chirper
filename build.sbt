import Dependencies._

ThisBuild / name              := """chirper"""
ThisBuild / organization      := "com.chirper"
ThisBuild / scalaVersion      := "3.8.4"
ThisBuild / version           := "0.1.0-SNAPSHOT"
ThisBuild / semanticdbEnabled := true

ThisBuild / scalacOptions := Seq(
  "-encoding",
  "UTF-8",
  "-no-indent",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-source:3.3",
  "-java-output-version:17",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-Wshadow:all",
  "-Xcheck-macros",
  "-Xmax-inlines:64"
)

Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val root = (project in file("."))
  // PlayLayoutPlugin comes along automatically and is what puts sources in app/,
  // config in conf/ and static files in public/.
  // PlayEbean enhances models.* bytecode at compile time (see ebean.default in application.conf)
  // so Ebean can do lazy loading and dirty-property tracking.
  // FrontendPlugin builds ui/ (the vendored twitter-spring-reactjs CRA app, see PORTING.md) onto
  // the classpath at /public; it declares noTrigger, so this is the only thing that turns it on.
  .enablePlugins(PlayJava, PlayEbean, FrontendPlugin)
  .settings(
    name := "chirper",

    // The vendored frontend's 2021-era dependency tree needs npm's pre-npm-7 peer-dependency
    // behavior; a plain `npm ci` fails on peer conflicts before it installs anything.
    frontendInstallCommand := Seq("npm", "ci", "--legacy-peer-deps"),

    // The reverse routers exist to build URLs from Twirl/Scala, and the JS one to feed Play's
    // jsRoutes. A Vite-built React SPA can use neither: it knows its own URLs, and its bundle is
    // never served by Twirl. Off, they stop generating and compiling ReverseUIController and
    // routes$javascript on every routes change. The forward router (router.Routes) is unaffected.
    generateReverseRouter   := false,
    generateJsReverseRouter := false,

    // Play writes a RUNNING_PID on startup and refuses to boot if one already exists. In a
    // container that file survives an unclean stop, so the next start fails with a stale pid --
    // the classic "works once, then never restarts" deployment failure.
    Universal / javaOptions += "-Dpidfile.path=/dev/null",
    libraryDependencies    ++= Seq(
      guice,
      // Persistence: the Ebean ORM (below) sits on top of Play's Database API (HikariCP pool,
      // via javaJdbc) with Evolutions managing the schema. H2 writes a file under ./data in dev;
      // production swaps db.default.url/driver for Postgres without touching code.
      javaJdbc,
      evolutions,
      "com.h2database" % "h2" % "2.3.232",
      // Password hashing (maintained bcrypt implementation; jbcrypt has been dormant since 2015)
      "at.favre.lib"        % "bcrypt"             % "0.10.2",
      "jakarta.inject"      % "jakarta.inject-api" % "2.0.1",
      "com.outr"           %% "scribe"             % "3.19.0",
      "org.playframework"  %% "play-ebean"         % "9.0.0-M2",
      "de.dentrassi.crypto" % "pem-keystore"       % "3.0.0",
      "com.outr"           %% "scribe-slf4j"       % "3.19.0",
      munit                 % Test,
      // play-test (from the Play plugin) provides WithApplication and JUnit 4 itself, but sbt
      // only *detects* JUnit tests through this framework adapter — without it `sbt test`
      // compiles the tests and then reports "Total 0".
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test
    )
  )
