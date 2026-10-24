# GPU Accelerator（我的世界 Java 1.20.1 Forge 模组）

让 **GPU 参与服务器加速运算** 的 Forge 服务端模组。它通过 OpenCL（LWJGL 绑定）把
两类最并行、最耗计算的工作——**地形生成** 与 **生物寻路**——卸载到显卡上执行，
把服务器 CPU 从大量重复的逐点计算中解放出来。

## 核心思路

Minecraft 服务端很多计算是**天然并行**的：地形密度场的每个体素、流场的每一格、每只怪的寻路
都互不依赖。这些任务在 CPU 上只能单线程逐点算，在 GPU 上却能铺到成千上万个 work-item 上同时算。

本模组内置了一个 **GPU 计算引擎**（dev.gpuaccel.gpu.GpuComputeEngine），当前运行三种内核：

- **3D 地形密度场**（洞穴、悬空地形、矿物、含水层、岩浆）
- **流场寻路**（生物的 GPU BFS 寻路）
- **2D 高度图**（早期基准用，保留作参考）

## 功能

### GPU 地形（自定义世界类型 "GPU Terrain"）
- **3D 密度地形** —— 洞穴、悬空/凸出地形、悬崖
- **矿物** —— 煤 / 铁 / 金 / 钻石，按深度分布
- **生物群系** —— 草原、沙漠、雪地、高山（气候噪声决定）
- **含水层 / 岩浆** —— 海平面以下洞穴灌水，-48 层以下灌岩浆
- **树木** —— 草原地表生成橡树

### GPU 生物 AI（流场寻路）
- 敌对生物顺着 **3D 流场**（8 水平方向 + 上下）朝最近的玩家移动，绕开障碍
- **每玩家独立流场** —— 每个在线玩家都有一张 GPU 算出的流场
- 只在玩家周围 **32 格**内吸引生物，移速较慢

### 客户端界面（按 G 键）
- GPU 设备型号 / 厂商 / 版本 / 显存 / 计算单元 / 占用率
- 服务器 CPU / 内存 / 世界存储大小
- **仅管理员（OP 等级 ≥ 2）可查看**

### 其他
- **CPU 回退**：无 GPU 时逐位一致回退，世界照常生成
- **Chunky 兼容**：高度/密度缓存有上限，大规模预生成不会爆内存
- **地狱/末地**沿用原版生成器
- GPU 引擎开机自动初始化

## 环境要求

- Minecraft **1.20.1** + Forge **47.x**（任意 47.x 均可）
- **Java 17**
- 支持 OpenCL 的显卡 + 驱动（NVIDIA / AMD / Intel 核显均可；无 GPU 时自动回退 CPU）

## 安装

产物有两个 jar（LWJGL 通过 jar-in-jar 打包，另带三平台 LWJGL 原生库）：

- **服务端**用 `gpuaccel-1.1.2-all.jar` → 放进 `mods/`（打包了 LWJGL 核心 + OpenCL，因为专用服务器不带 LWJGL）
- **客户端**用 `gpuaccel-1.1.2-client.jar` → 放进 `.minecraft/mods/`（只打包 OpenCL，客户端自带 LWJGL 核心）

## 使用

1. 新建世界时选「**GPU Terrain**」世界类型；或在 `server.properties` 里设 `level-type=gpuaccel:gpu_terrain` 并删除旧世界
2. 进游戏按 **G** 查看 GPU / 服务器状态
3. 可选：用 Chunky 预生成世界（缓存有上限，不会 OOM）

## 构建

    # 需要 JDK 17（PowerShell）
    $env:JAVA_HOME = "C:\path\to\jdk-17"
    .\gradlew.bat build

## 代码结构

    src/main/java/dev/gpuaccel/
    ├── GpuAcceleratorMod.java        @Mod 入口，开机初始化引擎、注册网络
    ├── gpu/
    │   ├── GpuComputeEngine.java     OpenCL 引擎：设备/上下文/内核/密度/流场
    │   ├── GpuDeviceInfo.java        设备信息
    │   └── LwjglNatives.java         解压并定位 LWJGL 核心原生库
    ├── worldgen/
    │   └── GpuChunkGenerator.java    GPU 3D 密度地形生成器
    ├── ai/
    │   ├── FlowField.java            GPU 流场
    │   └── MobFlowHandler.java       生物寻路（每玩家独立流场）
    ├── compute/
    │   └── CpuNoise.java             CPU 回退（与 GPU 逐位一致）
    ├── network/                      网络包（客户端 GUI 查询）
    └── client/                       客户端 GUI + 按键
    src/main/resources/
    ├── kernels/
    │   ├── heightmap.cl             2D 高度图内核（基准参考）
    │   ├── density3d.cl             3D 密度场内核（地形+矿物+岩浆）
    │   └── flowfield.cl             3D 流场内核（生物寻路）
    └── META-INF/mods.toml

## 如何扩展更多 GPU 工作负载

适合 GPU 的任务要满足「大量独立小计算、数据传输开销小于计算开销」。高度图/噪声/矩阵/流体扩散/
N 体物理/流场寻路都是最佳候选；强顺序依赖的 tick 逻辑不适合直接搬上 GPU。

## 许可

MIT
