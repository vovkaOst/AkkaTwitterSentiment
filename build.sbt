name := "SAG"

version := "1.0"

scalaVersion := "2.12.1"

resolvers += Resolver.sonatypeRepo("releases")

scalacOptions in ThisBuild ++= Seq("-language:postfixOps",
  "-language:implicitConversions",
  "-language:existentials",
  "-feature",
  "-deprecation")

  
libraryDependencies += "edu.stanford.nlp" % "stanford-corenlp" % "3.7.0"
libraryDependencies += "org.scala-lang" % "scala-library" % "2.12.1"

libraryDependencies ++= Seq(
  "com.danielasfregola" %% "twitter4s" % "5.0",
  "ch.qos.logback" % "logback-classic" % "1.1.9",
  "oauth.signpost" % "signpost-core" % "1.2",
  "oauth.signpost" % "signpost-commonshttp4" % "1.2",
  "commons-io" % "commons-io" % "2.4",
	"commons-lang" % "commons-lang" % "2.6",
  "com.typesafe.play" %% "play-json" % "2.6.0-M1",
  "org.apache.httpcomponents" % "httpclient" % "4.5.2",
  "org.apache.httpcomponents" % "httpcore" % "4.4.4"
)

libraryDependencies += "org.scalafx" %% "scalafx" % "8.0.102-R11"
libraryDependencies += "org.scalanlp" %% "breeze" % "0.13.1"
libraryDependencies += "org.scalanlp" %% "breeze-viz" % "0.13.1"
resolvers += "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/"
scalacOptions += "-Xexperimental"