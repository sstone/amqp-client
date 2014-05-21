name := "amqp-client"

organization := "com.github.sstone"
 
version := "1.3-SNAPSHOT"
 
scalaVersion := "2.10.1"

scalacOptions  ++= Seq("-feature", "-language:postfixOps")
 
resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies <<= scalaVersion { scala_version => 
    val akkaVersion   = "2.2.3"
    Seq(
        "com.typesafe.akka"    %% "akka-actor"          % akkaVersion,
        "com.rabbitmq"         % "amqp-client"          % "3.3.1",
        "com.typesafe.akka"    %% "akka-testkit"        % akkaVersion  % "test",
        "org.scalatest"        %% "scalatest"           % "1.9.1" % "test",
        "junit"           	   % "junit"                % "4.11" % "test",
        "com.typesafe.akka"    %  "akka-slf4j_2.10"     % akkaVersion,
        "ch.qos.logback"       %  "logback-classic"     % "1.0.0"
    )
}
