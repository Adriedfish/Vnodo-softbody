# Vnodo

Vettura 皮卡的节点式柔体碰撞附属模组。撞击后的凹陷、褶皱、梁缩短/断裂、三角破面都会保留；形变向周边车身扩散，碰撞盒跟随变形后的外壳。

Node-based soft-body crash addon for Vettura pickup trucks: persistent panel deformation, a physical crush field, and collision boxes driven by the deformed shell.

> 版本 **0.7.0-beta** · Minecraft 1.20.1 · Forge 47.x · Java 17

---

## 这是什么

Vnodo 只接管软体和渲染；Vettura 继续管理生命值、零件、轮胎、玻璃、修理与 HUD。

- 持久化钣金形变：内陷、褶皱、梁缩短/断裂、三角破面不会自动复原
- 物理压溃场：形变按物理规律向周边扩散，不是固定静态碰撞盒
- 碰撞盒跟随变形外壳：残骸的碰撞判定与外观一致
- 前后压溃分开限幅，高速前撞有扫掠检测，不会穿墙
- 工具箱修理：BODY 修理项完成即恢复软体
- 软体存档：按世界/维度/车辆 UUID 保存，重进自动恢复

## 构建

需要 JDK 17。

1. 把这两个依赖放进 `libs/`（**它们不随本仓库分发**，作者 Antaurora，All Rights Reserved）：
   - `vettura-1.2.1.jar`
   - `vextris-forge-1.20.1-0.1.4.2.jar`
2. 构建：

```bash
./gradlew build          # Windows: gradlew.bat build
./gradlew jar --offline --no-daemon   # 只出 jar
```

产物在 `build/libs/vnodo-<version>.jar`。没有 `libs/` 里的两个 jar 会编译失败，这是有意为之——不把别人的模组塞进仓库。

## 目录结构

```
src/main/java/com/bamng/core     软体节点/梁/三角面、压溃场、损伤图、快照
src/main/java/com/bamng/client   客户端运行时、渲染、设置页、调试 HUD
src/main/java/com/bamng/mixin    Vettura 的碰撞与损伤接管
src/main/resources               mods.toml、语言文件、README/CHANGELOG/LICENSE
tools/probe                      无头验证探针（不参与构建）
```

## 无头验证

这些类在 main 源集里，可以直接用 java 跑，不需要启动游戏：

```bash
java -cp "build/classes/java/main;build/resources/main;libs/*" com.bamng.core.BamngTestSuite
java -cp "build/classes/java/main;build/resources/main;libs/*" com.bamng.core.BamngT15CrashTest
javac -cp "build/classes/java/main;libs/*" -d tools/probe tools/probe/VnodoFrontSpeedProbe.java
java -cp "build/classes/java/main;build/resources/main;libs/*;tools/probe" VnodoFrontSpeedProbe
```

末行出现 `BAMNG SUITE OK`、`T15 CRASH OK`、`FRONT SPEED PROBE OK` 即通过。这是无头探针，不等于实机验证。

## 许可

CC BY-NC-SA 4.0。允许免费使用、学习、修改、二次开发、收录进免费整合包；二次分发必须署名并说明改动，衍生作品沿用同一协议；禁止商用与付费整合包；禁止植入恶意代码。详见 `LICENSE.txt`。

## 致谢

- [Vettura](https://www.curseforge.com/minecraft/mc-mods/vettura) 与 [Vextris](https://www.curseforge.com/minecraft/mc-mods/vextris)，作者 Antaurora。本模组是它们之上的第三方附属，不包含它们的任何代码或资源。
