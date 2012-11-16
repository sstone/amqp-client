organization := "com.aphelia"

name := "amqp-client"
 
version := "1.0"
 
scalaVersion := "2.10.0-RC2"

scalacOptions  += "-feature"
 
resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += "repo.codahale.com" at "http://repo.codahale.com"

libraryDependencies <<= scalaVersion { scala_version => 
    val akkaVersion   = "2.1.0-RC2"
    Seq(
        "com.typesafe.akka"    % "akka-kernel"         % akkaVersion cross CrossVersion.full,
        "com.typesafe.akka"    % "akka-actor"          % akkaVersion cross CrossVersion.full,
        "com.rabbitmq"         % "amqp-client"         % "2.8.1",
        "com.typesafe.akka"    % "akka-testkit"        % akkaVersion  % "test" cross CrossVersion.full,
        "org.specs2"           % "specs2"              % "1.12.2" % "test"  cross CrossVersion.full
    )
}
