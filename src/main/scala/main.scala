// 入口类已移至 PyTorchDynamicLoad.scala (com.example.PyTorchDynamicLoad)

@main
def main(): Unit = {

  import org.bytedeco.pytorch.global.torch
  val tensor = torch.ones(3, 3)
  println(s"\n🔥 3x3 全 1 张量：\n$tensor")

  torch.print(tensor)
  tensor.close()
}