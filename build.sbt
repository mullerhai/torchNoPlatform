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

libraryDependencies ++= Seq(
  // Source: https://mvnrepository.com/artifact/org.bytedeco/pytorch
  "org.bytedeco" % "pytorch" % "2.10.0-1.5.13",
//  "org.bytedeco" % "pytorch-platform" % "2.10.0-1.5.13",
//  "org.bytedeco" % "pytorch-platform-gpu" % "2.10.0-1.5.13",
//  //   Source: https://mvnrepository.com/artifact/org.bytedeco/cuda
//  "org.bytedeco" % "cuda" % "13.1-9.19-1.5.13",
//  //   Source: https://mvnrepository.com/artifact/org.bytedeco/cuda-platform
//  "org.bytedeco" % "cuda-platform" % "13.1-9.19-1.5.13",
//
//  "org.bytedeco" % "cuda-platform-redist-cudnn" % "13.1-9.19-1.5.13",
//  "org.bytedeco" % "cuda-platform-redist-cusolver" % "13.1-9.19-1.5.13",
//  "org.bytedeco" % "cuda-platform-redist-nccl" % "13.1-9.19-1.5.13",
  "junit" % "junit" % "4.13.2" // % Test
  // 注释掉的 MKL 依赖
  // "org.bytedeco" % "mkl-platform-redist" % "2025.2-1.5.13-SNAPSHOT"
)

// Add custom merge strategy for sbt-assembly to handle module-info.class deduplication
import sbtassembly.AssemblyPlugin.autoImport._
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "versions", "9", "module-info.class") => MergeStrategy.first
  case x => (assembly / assemblyMergeStrategy).value(x)
}
