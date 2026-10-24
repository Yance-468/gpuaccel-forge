# GPU Accelerator（我的世界 Java 1.20.1 Forge 模组）

让 **GPU 参与服务器加速运算** 的 Forge 服务端模组。它通过 OpenCL（LWJGL 绑定）把
适合并行的计算——地形高度图生成、CPU/GPU 基准对比——卸载到显卡上执行，把服务器 CPU
从大量重复的逐点计算中解放出来。

## 核心思路

Minecraft 服务端的很多计算是**天然并行**的：每个 (x, z) 高度图柱、每个区块的噪声采样
互不依赖。这些任务在 CPU 上只能单线程逐点算，但在 GPU 上可以铺到成千上万个 work-item
上同时算。

本模组内置了一个 **GPU 计算引擎**（dev.gpuaccel.gpu.GpuComputeEngine），并演示了
一个具体工作负载：**多倍频值噪声 fBm 高度图生成**。

## 实测效果（NVIDIA GTX 960，OpenCL 3.0）

| 规模 | GPU | CPU（单线程） | 加速比 |
|------|-----|--------------|--------|
| 1024×1024（约 105 万点） | 4.9 ms | 176 ms | 约 36× |

GPU 与 CPU 输出**逐位一致**（两者使用同一整数哈希值噪声），因此可以安全地互为回退。

## 原理

- LWJGL OpenCL 绑定是**无 JNI** 的：它直接加载显卡驱动自带的 OpenCL ICD 加载器
  （Windows 上是 OpenCL.dll，Linux 上是 libOpenCL.so）。所以**不需要**额外的 OpenCL 原生库。
- 唯一需要的是 LWJGL **核心**原生库（lwjgl.dll / liblwjgl.so）。Minecraft 自带它，
  但为了健壮性，本模组把三个平台的核心原生库打进 jar，运行时按需解压并设置
  org.lwjgl.librarypath（见 dev.gpuaccel.gpu.LwjglNatives）。
- lwjgl-opencl 的 Java 绑定类通过 Forge 的 **jar-in-jar** 机制打进模组 jar。

## 环境要求

- Minecraft **1.20.1** + Forge **47.2.0**（或 47.x）
- **Java 17**
- 一张支持 OpenCL 的显卡（NVIDIA / AMD / Intel 核显均可；需安装对应驱动）

## 构建

    # 需要 JDK 17（PowerShell）
    $env:JAVA_HOME = "C:\path\to\jdk-17"
    .\gradlew.bat build

产物有两个 jar（LWJGL 通过 jar-in-jar 打包，另带三平台 LWJGL 原生库）：

- **服务端**用 `gpuaccel-1.0.2-all.jar`（打包了 LWJGL 核心 + OpenCL，因为专用服务器不带 LWJGL）
- **客户端**用 `gpuaccel-1.0.2-client.jar`（只打包 OpenCL，因为客户端自带 LWJGL 核心，再打包会冲突）

## 自动启动

服务器启动时，模组会自动初始化 GPU 引擎（检测 OpenCL 设备并编译内核），结果写入日志：

- `GPU compute engine ready: ...` —— GPU 可用
- `GPU compute engine unavailable: ...` —— 没有可用 GPU，世界生成自动回退到 CPU（结果一致）

## 客户端界面（查看 GPU 占用）

客户端也装上本模组后，按 **G** 键打开 GPU 状态界面，实时查看服务器 GPU 的：

- 设备型号、厂商、OpenCL 版本、显存大小、计算单元数
- 已生成高度图次数、最近一次 / 总 / 平均耗时

（按键可在「选项 → 按键绑定」里修改）

## 自定义世界类型（v1.0.1，接入原版世界生成）

本模组新增了一个 **GPU Terrain** 世界预设：创建新世界时在「世界类型」里选 **GPU Terrain**，或在服务器的 server.properties 里设置：

    level-type=gpuaccel:gpu_terrain

它使用 GpuChunkGenerator：地形表面高度由 GPU 按区域（256×256 列）并行生成并缓存，每个 (x,z) 列独立计算——正是世界生成中最适合 GPU 的一类工作；没有 GPU 时自动回退到 CPU（结果一致）。

主世界用 GPU 地形，**地狱/末地沿用原版生成器**；高度缓存有上限（约 128 MB），可放心配合 **Chunky** 大规模预生成而不会占满内存。

## 代码结构

    src/main/java/dev/gpuaccel/
    ├── GpuAcceleratorMod.java        @Mod 入口，注册命令、懒加载引擎
    ├── gpu/
    │   ├── GpuComputeEngine.java     OpenCL 引擎：设备发现/上下文/内核编译/高度图
    │   ├── GpuDeviceInfo.java        设备信息
    │   └── LwjglNatives.java         解压并定位 LWJGL 核心原生库
    ├── compute/
    │   └── CpuNoise.java             CPU 参考实现（与 GPU 逐位一致，作回退）
    └── command/
        └── GpuCommand.java           /gpuaccel 命令
    src/main/resources/
    ├── kernels/heightmap.cl          OpenCL C 内核源码
    └── META-INF/mods.toml

## 如何扩展更多 GPU 工作负载

1. 在 src/main/resources/kernels/ 下新增 OpenCL C 内核（例如红石/流体扩散、N 体物理、
   区块噪声采样）。
2. 在 GpuComputeEngine 中加载并 clCreateKernel 新内核，封装成一个方法。
3. 在 GpuCommand 中加一个子命令即可。

> 提示：适合 GPU 的任务要满足「大量独立小计算、数据传输开销小于计算开销」。
> 高度图/噪声/矩阵类任务是最佳候选；强顺序依赖的 tick 逻辑（红石线、实体 AI）不适合直接搬上 GPU。

## 许可

MIT
