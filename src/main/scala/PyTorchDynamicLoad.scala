package com.example

import java.io.{File, FileOutputStream, InputStream}
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * SBT 版 JavaCPP-PyTorch 动态加载平台 JAR 示例
 * 版本：pytorch 2.10.0-1.5.13 + javacpp 1.5.13
 * 平台：macOS arm64 (Apple Silicon)
 *
 * 解决方案（Java 9+ 兼容）：
 *  1. 从平台 JAR 提取所有 .dylib 到临时目录
 *  2. 按正确依赖顺序用 System.load(绝对路径) 加载（绕过 java.library.path）
 *  3. 所有 native 库已进 JVM 后，直接用 Class.forName 触发 torch static init
 *     → 不调用 Loader.load()，彻底避免其 System.loadLibrary fallback
 */
object PyTorchDynamicLoad {

  private val LIB_DIR = new File("./libs")

  // 明确的加载顺序：基础依赖库 → JNI 桥接库
  // 基础库按依赖从底向上，JNI 桥接库最后
  private val LOAD_ORDER = Seq(
    // --- openblas 依赖链 ---
    "libgcc_s.1.1.dylib",
    "libquadmath.0.dylib",
    "libgfortran.5.dylib",
    "libgfortran.dylib",
    "libopenblas.0.dylib",
    "libopenblas_nolapack.0.dylib",   // 复制自 libopenblas.0.dylib，libjniopenblas_nolapack 的依赖
    // --- pytorch 依赖链 ---
    "libc10.dylib",
    "libiomp5.dylib",
    "libtorch_cpu.dylib",
    "libtorch.dylib",
    // --- JNI 桥接层（必须最后，依赖上面所有库）---
    "libjnijavacpp.dylib",
    "libjniopenblas_nolapack.dylib",
    "libjniopenblas.dylib",
    "libjniopenblas_full.dylib",
    "libjnitorch.dylib"
  )

  def main(args: Array[String]): Unit = {
    // 1. 提取所有 .dylib 到临时目录
    val nativeDir = extractNativeLibs()
    println(s"✅ 原生库已提取至：$nativeDir")

    // 2. 按顺序 System.load(绝对路径) —— 完全绕过 java.library.path 和 Loader
    loadInOrder(nativeDir)
    println("✅ 所有原生库预加载完成")

    // 3. 触发 torch 的 static initializer（此时所有 native 已在 JVM 中）
    //    用 Class.forName 而非 Loader.load()，避免 Loader 再次尝试 System.loadLibrary
    Class.forName("org.bytedeco.openblas.global.openblas_nolapack")// 预加载 openblas_nolapack，满足 torch 的隐式依赖，否则必报
    Class.forName("org.bytedeco.pytorch.global.torch")
    println("✅ PyTorch 初始化成功")

    // 4. 功能测试
    testPyTorch()
  }

  private def extractNativeLibs(): File = {
    if (!LIB_DIR.exists() || LIB_DIR.listFiles() == null)
      throw new RuntimeException("❌ 请创建 ./libs 目录，并放入 Mac 平台 JAR")

    val jars = LIB_DIR.listFiles().filter(_.getName.endsWith(".jar"))
    if (jars.isEmpty)
      throw new RuntimeException("❌ ./libs 目录下没有 JAR 文件")

    val tmpDir = Files.createTempDirectory("javacpp-native-").toFile
    tmpDir.deleteOnExit()

    for (jar <- jars) {
      println(s"📦 扫描：${jar.getName}")
      val zf = new ZipFile(jar)
      try {
        val entries = zf.entries()
        while (entries.hasMoreElements) {
          val entry = entries.nextElement()
          val name = entry.getName
          if (!entry.isDirectory && name.endsWith(".dylib")) {
            val libName = name.substring(name.lastIndexOf('/') + 1)
            val outFile = new File(tmpDir, libName)
            if (!outFile.exists()) {
              val in: InputStream = zf.getInputStream(entry)
              try {
                val fos = new FileOutputStream(outFile)
                try { in.transferTo(fos) } finally { fos.close() }
              } finally { in.close() }
              println(s"  ↳ 提取：$libName")
            }
          }
        }
      } finally { zf.close() }
    }
    // libjniopenblas_nolapack.dylib 的 @rpath 依赖 libopenblas_nolapack.0.dylib，
    // 但平台 JAR 只提供了 libopenblas.0.dylib（全功能版，实现相同）。
    // 动态链接器在 @rpath 下寻找 libopenblas_nolapack.0.dylib（即 tmpDir/ 下），
    // 直接复制一份即可满足依赖。
    val openblasNolapack = new File(tmpDir, "libopenblas_nolapack.0.dylib")
    val openblasSource   = new File(tmpDir, "libopenblas.0.dylib")
    if (!openblasNolapack.exists() && openblasSource.exists()) {
      Files.copy(openblasSource.toPath, openblasNolapack.toPath)
      println(s"  ↳ 复制：libopenblas_nolapack.0.dylib（来自 libopenblas.0.dylib）")
    }

    tmpDir
  }


  private def loadInOrder(dir: File): Unit = {
    // 先按 LOAD_ORDER 加载已知库
    println("\n🔗 按依赖顺序加载原生库：")
    val loaded = scala.collection.mutable.Set.empty[String]

    for (name <- LOAD_ORDER) {
      val f = new File(dir, name)
      if (f.exists()) {
        try {
          System.load(f.getAbsolutePath)
          println(s"  ✅ $name")
          loaded += name
        } catch {
          case e: UnsatisfiedLinkError =>
            // 已被加载（同一进程内重复 load 同路径会抛异常）则忽略
            if (e.getMessage != null && e.getMessage.contains("already loaded")) {
              println(s"  ↩️  $name（已加载）")
              loaded += name
            } else {
              println(s"  ❌ $name：${e.getMessage}")
              throw e
            }
        }
      } else {
        println(s"  ⚠️  $name 不存在，跳过")
      }
    }

    // 加载 LOAD_ORDER 未列出的剩余库（以防万一）
    val remaining = dir.listFiles().filter(f => f.getName.endsWith(".dylib") && !loaded.contains(f.getName))
    for (f <- remaining) {
      try {
        System.load(f.getAbsolutePath)
        println(s"  ✅ ${f.getName}（补充）")
        loaded += f.getName
      } catch {
        case e: UnsatisfiedLinkError =>
          if (e.getMessage != null && e.getMessage.contains("already loaded")) {
            println(s"  ↩️  ${f.getName}（已加载）")
            loaded += f.getName
          } else
            println(s"  ⚠️  ${f.getName}（跳过：${e.getMessage}）")
      }
    }

    // 将所有已加载库注入 JavaCPP Loader 的 loadedLibraries 缓存，
    // 使 torch.<clinit> → Loader.load() 时跳过 System.loadLibrary 调用
    injectLoadedLibraries(dir, loaded.toSet)
  }

  /**
   * 向 Loader.loadedLibraries (Map<String,String>) 注入已加载库的记录。
   * key = 不带 "lib" 前缀和 ".dylib" 后缀的库名（即 Loader 使用的 short name），
   * value = 绝对路径。
   * 这样 Loader.loadLibrary 在查 map 时会认为库已加载而直接跳过。
   */
  private def injectLoadedLibraries(dir: File, loaded: Set[String]): Unit = {
    try {
      val loaderClass = classOf[org.bytedeco.javacpp.Loader]
      val field = loaderClass.getDeclaredField("loadedLibraries")
      field.setAccessible(true)
      val map = field.get(null).asInstanceOf[java.util.Map[String, String]]
      for (name <- loaded) {
        // lib<name>.dylib → <name>
        val shortName = name.stripPrefix("lib").stripSuffix(".dylib")
          .replaceAll("\\.\\d+$", "")   // 去掉 .0、.5、.1.1 等版本后缀
        val absPath = new File(dir, name).getAbsolutePath
        map.put(shortName, absPath)
      }
      println(s"  ✅ 已将 ${loaded.size} 个库注入 Loader.loadedLibraries")
    } catch {
      case e: Exception =>
        println(s"  ⚠️  注入 Loader.loadedLibraries 失败：${e.getMessage}")
    }
  }

  private def testPyTorch(): Unit = {
    import org.bytedeco.pytorch.global.torch
    val tensor = torch.ones(3, 3)
    println(s"\n🔥 3x3 全 1 张量：\n$tensor")

    torch.print(tensor)
    tensor.close()
  }
}
