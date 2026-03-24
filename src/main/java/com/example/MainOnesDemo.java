package com.example;

import org.bytedeco.pytorch.Tensor;
import org.bytedeco.pytorch.global.torch;

/**
 * Java 入口：等价于 Scala 中的
 *
 * @main
 * def main(): Unit = {
 *   import org.bytedeco.pytorch.global.torch
 *   val tensor = torch.ones(3, 3)
 *   println(s"\n🔥 3x3 全 1 张量：\n$tensor")
 *   torch.print(tensor)
 *   tensor.close()
 * }
 */
public class MainOnesDemo {

    public static void main(String[] args) {
        // 创建 3x3 全 1 张量
        Tensor tensor = torch.ones(3, 3);
        try {
            System.out.println("\n🔥 3x3 全 1 张量：\n" + tensor);
            torch.print(tensor);
        } finally {
            // 释放原生资源
            if (tensor != null) {
                tensor.close();
            }
        }
    }
}

