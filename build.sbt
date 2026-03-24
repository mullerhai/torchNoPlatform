ThisBuild / version := "0.1.0"

ThisBuild / scalaVersion := "3.8.2"
javaOptions ++= Seq(
  "--add-opens", "java.base/java.util=ALL-UNNAMED",
  "--add-opens", "java.base/java.lang=ALL-UNNAMED",
  "--add-opens", "java.base/java.net=ALL-UNNAMED",
  "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
  "--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED"
)

val vectorArgs = Seq(
  "--add-modules", "jdk.incubator.vector",
  "--add-exports", "java.base/jdk.internal.vm.vector=ALL-UNNAMED"
)

scalacOptions ++= Seq(
)

fork := true

// 4. 运行和编译期 JVM 选项：解决 IllegalAccessError
javaOptions ++= Seq(
  "--add-modules", "jdk.incubator.vector",
  "--add-exports", "java.base/jdk.internal.vm.vector=ALL-UNNAMED"
)
resolvers += Resolver.mavenLocal

lazy val root = (project in file("."))
  .settings(
    name := "torchNoPlatform"
  )

// 平台相关配置：当前项目只针对 macOS ARM64
val javaCppVersion   = "1.5.13"
val pytorchVersion   = s"2.10.0-$javaCppVersion"
val openblasVersion  = s"0.3.31-$javaCppVersion"
val javaCppPlatform  = "macosx-arm64"

libraryDependencies ++= Seq(
  // Compile：macOS ARM64 瘦包 + 胶水层
  "org.bytedeco" % "javacpp"  % javaCppVersion,
  "org.bytedeco" % "openblas" % openblasVersion,
  "org.bytedeco" % "pytorch"  % pytorchVersion,
  ("org.bytedeco" % "javacpp"  % javaCppVersion  classifier javaCppPlatform)  %Provided, // % Test,
  ("org.bytedeco" % "openblas" % openblasVersion classifier javaCppPlatform)  %Provided, //% Test,
  ("org.bytedeco" % "pytorch"  % pytorchVersion  classifier javaCppPlatform)  %Provided, //% Test,
  // Test：platform 聚合包，只用于测试你的动态加载逻辑
//  "org.bytedeco" % "pytorch-platform"     % pytorchVersion % Test,
//  "org.bytedeco" % "pytorch-platform-gpu" % pytorchVersion % Test,
  "junit" % "junit" % "4.13.2" % Test
  // "org.bytedeco" % "mkl-platform-redist" % "2025.2-1.5.13-SNAPSHOT"
)

// 删掉之前对 org.bytedeco 的全局 exclude，避免把瘦包也排掉

// Add custom merge strategy for sbt-assembly to handle module-info.class deduplication
import sbtassembly.AssemblyPlugin.autoImport._
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "versions", "9", "module-info.class") => MergeStrategy.first
  case PathList("META-INF", "native-image", _ @ _*) => MergeStrategy.discard
  case x => (assembly / assemblyMergeStrategy).value(x)
}

assemblyExcludedJars := {
  val cp = (fullClasspath in assembly).value
  cp filter { jar =>
    jar.data.getName.startsWith("pytorch-platform") ||
    jar.data.getName.startsWith("pytorch-platform-gpu") ||
    jar.data.getName.endsWith("macosx-arm64") ||
      jar.data.getName.endsWith("macosx-x86_64") ||
      jar.data.getName.endsWith("linux-arm64")||
      jar.data.getName.endsWith("linux-x86_64")||
      jar.data.getName.endsWith("windows-arm64") ||
      jar.data.getName.endsWith("windows-x86_64")
  }
}
