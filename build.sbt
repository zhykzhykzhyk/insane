scalacOptions += "-deprecation"

scalacOptions += "-feature"

scalacOptions += "-unchecked"

scalaVersion := "2.10.0"

// scalaHome := Some(file("/home/ekneuss/scala/scala-git/"))

// libraryDependencies += "org.squeryl" %% "squeryl" % "0.9.4"

libraryDependencies += "com.h2database" % "h2" % "1.2.127"

libraryDependencies += "mysql" % "mysql-connector-java" % "5.1.15"

libraryDependencies += "org.scala-lang" % "scala-compiler" % "2.10.0"

(scalaSource in Compile) <<= sourceDirectory apply { bd =>   bd / "insane" }
