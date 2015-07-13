// Comment to get more information during initialization
logLevel := Level.Warn

resolvers += "Gawker Public Group" at "https://nexus.kinja-ops.com/nexus/content/groups/public"

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

// The Typesafe repository
resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"
