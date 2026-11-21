# Vnodo 更新日志 / Changelog

## 0.7.0-beta

第一个公开测试版。Vnodo 是 Vettura 皮卡的节点式柔体附属层，Vettura 继续管理生命值、零件、轮胎、玻璃、修理与 HUD，Vnodo 负责软体节点/梁/三角面、塑性形变、压溃场和渲染，并提供碰撞盒场给 Vettura 使用。

### 中文

- 新增持久化钣金形变：撞击后的凹陷、褶皱、梁缩短/断裂、三角破面都会保留，不会自动弹回。
- 新增物理压溃场：形变按物理规律向周边车身扩散，不再是固定静态碰撞盒。
- 碰撞盒跟随变形外壳：变形后的碰撞判定与外观形状一致。
- 前后压溃分开限幅：前部按前保险杠/机盖路径溃缩，后尾与尾门单独限幅，倒车不再一碰就软。
- 高速前撞修复：刚性速度前馈 + 扫掠式跟随夹取，100 km/h 级别不再穿墙或第一帧无接触。
- 压溃深度按速度平方增长，并加每 tick 塑性上限，低速只小变形，高速才进入对应压溃。
- 工具箱修理：车身 BODY 修理项完成即恢复软体，不必等轮胎/发动机/玻璃全部修完。
- 软体存档：按世界/维度/车辆 UUID 保存，重进世界自动恢复；修理后自动删除旧档。
- 配置锁定为硬预设，设置页隐藏默认/软预设按钮，避免误调把车调软。
- 性能收尾：碰撞场只在版本变化时同步，静态残骸不再每两 tick 发一次包；每帧刚性跟随不再为每个节点新建数组。
- jar 内附带 README.md、LICENSE.txt 与本更新日志。

### English

- Persistent panel deformation: dents, creases, shortened/broken beams and torn triangles stay after impact.
- Physical crush field: deformation propagates across the shell instead of using a fixed static hitbox.
- Collision boxes follow the deformed shell, so the wreck collides like it looks.
- Separate front and rear crush limits: the nose folds progressively, the tailgate stays firm in reverse.
- High-speed frontal fix: rigid velocity feed-forward plus swept follow so a 100 km/h hit neither tunnels through a wall nor misses on the first frame.
- Crush depth grows with the square of impact speed and is capped per tick.
- Repair: the shell resets when the BODY repair job completes, without waiting for tyres/engine/glass.
- Soft-body save state per world/dimension/vehicle UUID, restored on world load and deleted on repair.
- Physics scales are locked to the hard preset in the settings screen.
- Performance: the collision field is only synced when its version changes, and the rigid follow no longer allocates per node per tick.
- README.md, LICENSE.txt and this changelog ship inside the jar.

### 已知问题 / Known issues

- 部分外观件仍未被软体壳覆盖，撞击后这些部位表现接近刚性。
- 被动状态（未驾驶、被推挤时的形变）仍不理想。
- 服务端碰撞场同步、多人联机下的碰撞盒一致性尚未在真实联机环境验证。
- 存档只保存外形相关状态，重进后强度历史不完全一致。
- 本版为 Beta，建议备份存档后再使用。

### 依赖 / Dependencies

- Vettura 1.2.0 及以上（必需）
- Minecraft 1.20.1 / Forge 47.x
