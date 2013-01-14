name := "amqp-client"

organization := "com.github.sstone"
 
version := "1.1-SNAPSHOT"
 
scalaVersion := "2.10.0"

scalacOptions  += "-feature"
 
resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies <<= scalaVersion { scala_version => 
    val akkaVersion   = "2.1.0"
    Seq(
        "com.typesafe.akka"    %% "akka-actor"          % akkaVersion,
        "com.rabbitmq"         % "amqp-client"         % "3.0.1",
        "com.typesafe.akka"    %% "akka-testkit"        % akkaVersion  % "test",
        "org.specs2"           %% "specs2"              % "1.13" % "test", 
        "junit"           	% "junit"              % "4.8.2" % "test" 
    )
}
