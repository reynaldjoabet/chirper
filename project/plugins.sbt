addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")
addSbtPlugin("org.playframework" % "sbt-plugin" % "3.1.0-M9")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")
// Enhances model bytecode at compile time for Ebean (lazy loading, dirty-property tracking).
// Version tracks the play-ebean runtime library declared in build.sbt.
addSbtPlugin("org.playframework" % "sbt-play-ebean" % "9.0.0-M2")

// addSbtPlugin("com.github.sbt" % "sbt-javaagent" % "0.1.8")
