name := "amqp-client"

organization := "com.aphelia"
 
version := "1.1-SNAPSHOT"
 
scalaVersion := "2.10.0"

scalacOptions  += "-feature"
 
resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += "repo.codahale.com" at "http://repo.codahale.com"

libraryDependencies <<= scalaVersion { scala_version => 
    val akkaVersion   = "2.1.0"
    Seq(
        "com.typesafe.akka"    % "akka-kernel"         % akkaVersion cross CrossVersion.binary,
        "com.typesafe.akka"    % "akka-actor"          % akkaVersion cross CrossVersion.binary,
        "com.rabbitmq"         % "amqp-client"         % "2.8.1",
        "com.typesafe.akka"    % "akka-testkit"        % akkaVersion  % "test" cross CrossVersion.binary,
        "org.specs2"           % "specs2"              % "1.13" % "test"  cross CrossVersion.binary
    )
}
