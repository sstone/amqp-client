name := "amqp-client"

organization := "com.github.sstone"

version := "1.5-SNAPSHOT"

scalaVersion := "2.12.1"

crossScalaVersions := Seq("2.11.8", "2.12.1")

scalacOptions ++= Seq("-feature", "-language:postfixOps")

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++= {
  val akkaVersion = "2.4.17"
  Seq(
    "com.rabbitmq"         % "amqp-client"          % "4.1.0",
    "com.typesafe.akka"    %% "akka-actor"          % akkaVersion % "provided",
    "com.typesafe.akka"    %% "akka-slf4j"          % akkaVersion % "test",
    "com.typesafe.akka"    %% "akka-testkit"        % akkaVersion % "test",
    "org.scalatest"        %% "scalatest"           % "3.0.1" % "test",
    "ch.qos.logback"       % "logback-classic"      % "1.2.1" % "test",
    "junit"           	   % "junit"                % "4.12" % "test"
  )
}
