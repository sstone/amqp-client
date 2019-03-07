name := "amqp-client"

organization := "com.kinja"
 
version := "2.2.0" + (if (RELEASE_BUILD) "" else "-SNAPSHOT")
 
crossScalaVersions := Seq("2.12.6", "2.11.8")

scalaVersion := crossScalaVersions.value.head
 
val akkaVersion = "2.5.11"

scalacOptions ++= Seq(
	"-feature",
	"-deprecation",
	"-language:postfixOps"
)

libraryDependencies ++= Seq(
	"com.typesafe.akka"    %% "akka-actor"          % akkaVersion % Provided,
	"com.rabbitmq"         % "amqp-client"          % "5.6.0",
	"com.typesafe.akka"    %% "akka-testkit"        % akkaVersion  % Test,
	"org.scalatest"        %% "scalatest"           % "3.0.5" % Test,
	"junit"                % "junit"                % "4.12" % Test,
	"com.typesafe.akka"    %% "akka-slf4j"          % akkaVersion % Provided,
	"ch.qos.logback"       %  "logback-classic"     % "1.0.0" % Provided
)

testOptions in Test := Seq.empty
