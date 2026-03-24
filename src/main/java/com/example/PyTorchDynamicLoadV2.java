package com.example;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.pytorch.global.torch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class PyTorchDynamicLoadV2 {

    ///Users/mullerzhang/IdeaProjects/torchNoPlatform/libs/javacpp-1.5.13-macosx-arm64.jar
    private static final File LIB_DIR = new File("/Users/mullerzhang/IdeaProjects/torchNoPlatform/libs");
//    private static final File LIB_DIR = new File("./libs");

    private static final String[] LOAD_ORDER = new String[]{
            "libgcc_s.1.1.dylib",
            "libquadmath.0.dylib",
            "libgfortran.5.dylib",
            "libgfortran.dylib",
            "libopenblas.0.dylib",
            "libopenblas_nolapack.0.dylib",
            
            "libc10.dylib",
            "libiomp5.dylib",
            "libtorch_cpu.dylib",
            "libtorch.dylib",
            
            "libjnijavacpp.dylib",
            "libjniopenblas_nolapack.dylib",
            "libjniopenblas.dylib",
            "libjniopenblas_full.dylib",
            "libjnitorch.dylib"
    };

    public static void main(String[] args) throws Exception {
        System.out.println("✅ 加载 PyTorch 原生库示例Java（V2）"+"\n"+ LIB_DIR.getAbsolutePath());
        boolean ok = tryInitViaPlatform();
        if (!ok) {
            File nativeDir = extractNativeLibs();
            System.out.println("✅ 原生库已提取至：" + nativeDir);

            loadInOrder(nativeDir);
            System.out.println("✅ 所有原生库预加载完成");

            // 尝试再次通过我们手动加载的库初始化 openblas_nolapack 和 torch
            try {
                Class.forName("org.bytedeco.openblas.global.openblas_nolapack");
                Class.forName("org.bytedeco.pytorch.global.torch");
                System.out.println("✅ PyTorch 初始化成功（通过 libs 目录）");
            } catch (UnsatisfiedLinkError | ExceptionInInitializerError e) {
                System.out.println("⚠️  使用 libs 目录初始化 torch 时仍然出现 UnsatisfiedLinkError：" + e.getMessage());
            }
        } else {
            System.out.println("✅ 通过 classpath 上的 pytorch-platform 初始化成功");
        }

        testPyTorch();
    }

    private static boolean tryInitViaPlatform() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try {
            Class<?> torchClass = Class.forName("org.bytedeco.pytorch.global.torch", false, cl);
            Class.forName("org.bytedeco.openblas.global.openblas_nolapack", false, cl);
            System.out.println("ℹ️ 检测到 org.bytedeco.pytorch.global.torch 在 classpath 上，尝试使用 platform 依赖");
            try {
                torch.ones(1).close();
//                torchClass.getMethod("get_num_threads").invoke(null);
                return true;
            } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
                System.out.println("ℹ️ 通过 platform 依赖初始化失败，将回退到 libs 目录加载：" + e.getMessage());
                return false;
            }
        } catch (ClassNotFoundException e) {
            System.out.println("ℹ️ 未在 classpath 上找到 org.bytedeco.pytorch.global.torch，跳过 platform 依赖路径");
            return false;
        } catch (Throwable t) {
            System.out.println("ℹ️ 通过 platform 依赖初始化出错，将回退到 libs 目录加载：" + t.getMessage());
            return false;
        }
    }

    private static File extractNativeLibs() {
        if (!LIB_DIR.exists() || LIB_DIR.listFiles() == null) {
            throw new RuntimeException("❌ 请创建 ./libs 目录，并放入 Mac 平台 JAR");
        }

        File[] jars = LIB_DIR.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            throw new RuntimeException("❌ ./libs 目录下没有 JAR 文件");
        }

        Path tmp;
        try {
            tmp = Files.createTempDirectory("javacpp-native-");
        } catch (IOException e) {
            throw new RuntimeException("创建临时目录失败", e);
        }
        File tmpDir = tmp.toFile();
        tmpDir.deleteOnExit();

        for (File jar : jars) {
            System.out.println("📦 扫描：" + jar.getName());
            try (ZipFile zf = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.endsWith(".dylib")) {
                        String libName = name.substring(name.lastIndexOf('/') + 1);
                        File outFile = new File(tmpDir, libName);
                        if (!outFile.exists()) {
                            try (InputStream in = zf.getInputStream(entry);
                                 FileOutputStream fos = new FileOutputStream(outFile)) {
                                in.transferTo(fos);
                            }
                            System.out.println("  ↳ 提取：" + libName);
                        }
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("读取 JAR 失败: " + jar, e);
            }
        }

        File openblasNolapack = new File(tmpDir, "libopenblas_nolapack.0.dylib");
        File openblasSource = new File(tmpDir, "libopenblas.0.dylib");
        if (!openblasNolapack.exists() && openblasSource.exists()) {
            try {
                Files.copy(openblasSource.toPath(), openblasNolapack.toPath());
                System.out.println("  ↳ 复制：libopenblas_nolapack.0.dylib（来自 libopenblas.0.dylib）");
            } catch (IOException e) {
                throw new RuntimeException("复制 libopenblas_nolapack.0.dylib 失败", e);
            }
        }

        return tmpDir;
    }

    private static void loadInOrder(File dir) {
        System.out.println("\n🔗 按依赖顺序加载原生库：");
        Set<String> loaded = new HashSet<>();

        for (String name : LOAD_ORDER) {
            File f = new File(dir, name);
            if (f.exists()) {
                try {
                    System.load(f.getAbsolutePath());
                    System.out.println("  ✅ " + name);
                    loaded.add(name);
                } catch (UnsatisfiedLinkError e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("already loaded")) {
                        System.out.println("  ↩️  " + name + "（已加载）");
                        loaded.add(name);
                    } else {
                        System.out.println("  ❌ " + name + "：" + e.getMessage());
                        throw e;
                    }
                }
            } else {
                System.out.println("  ⚠️  " + name + " 不存在，跳过");
            }
        }

        File[] all = dir.listFiles((d, n) -> n.endsWith(".dylib"));
        if (all != null) {
            for (File f : all) {
                if (loaded.contains(f.getName())) continue;
                try {
                    System.load(f.getAbsolutePath());
                    System.out.println("  ✅ " + f.getName() + "（补充）");
                    loaded.add(f.getName());
                } catch (UnsatisfiedLinkError e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("already loaded")) {
                        System.out.println("  ↩️  " + f.getName() + "（已加载）");
                        loaded.add(f.getName());
                    } else {
                        System.out.println("  ⚠️  " + f.getName() + "（跳过：" + e.getMessage() + ")");
                    }
                }
            }
        }

        injectLoadedLibraries(dir, loaded);
    }

    private static void injectLoadedLibraries(File dir, Set<String> loaded) {
        try {
            Class<Loader> loaderClass = Loader.class;
            java.lang.reflect.Field field = loaderClass.getDeclaredField("loadedLibraries");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> map = (Map<String, String>) field.get(null);
            for (String name : loaded) {
                String shortName = name;
                if (shortName.startsWith("lib")) {
                    shortName = shortName.substring(3);
                }
                if (shortName.endsWith(".dylib")) {
                    shortName = shortName.substring(0, shortName.length() - ".dylib".length());
                }
                shortName = shortName.replaceAll("\\.\\d+$", "");
                String absPath = new File(dir, name).getAbsolutePath();
                map.put(shortName, absPath);
            }
            System.out.println("  ✅ 已将 " + loaded.size() + " 个库注入 Loader.loadedLibraries");
        } catch (Exception e) {
            System.out.println("  ⚠️  注入 Loader.loadedLibraries 失败：" + e.getMessage());
        }
    }

    private static void testPyTorch() {
        var tensor = torch.ones(3, 3);
        System.out.println("\n🔥 3x3 全 1 张量：\n" + tensor);
        torch.print(tensor);
        tensor.close();
    }
}
