name := "amqp-client"

organization := "com.kinja"
 
version := "1.5.0" + {if (System.getProperty("JENKINS_BUILD") == null) "-SNAPSHOT" else ""}
 
scalaVersion := "2.11.6"

scalacOptions  ++= Seq("-feature", "-language:postfixOps")
 
resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

resolvers += "Gawker Public Group" at "https://nexus.kinja-ops.com/nexus/content/groups/public/"

libraryDependencies <<= scalaVersion { scala_version =>
    val akkaVersion   = "2.3.12"
    Seq(
        "com.typesafe.akka"    %% "akka-actor"          % akkaVersion % "provided",
        "com.rabbitmq"         % "amqp-client"          % "3.2.1",
        "com.typesafe.akka"    %% "akka-testkit"        % akkaVersion  % "test",
        "org.scalatest"        %% "scalatest"           % "2.2.4" % "test",
        "junit"           	   % "junit"                % "4.11" % "test",
        "com.typesafe.akka"    %% "akka-slf4j"          % akkaVersion % "provided",
        "ch.qos.logback"       %  "logback-classic"     % "1.0.0" % "provided"
    )
}

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

publishTo <<= (version)(version =>
    if (version endsWith "SNAPSHOT") Some("Gawker Snapshots" at "https://nexus.kinja-ops.com/nexus/content/repositories/snapshots/")
    else                             Some("Gawker Releases" at "https://nexus.kinja-ops.com/nexus/content/repositories/releases/")
)
