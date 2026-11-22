# Vnodo 更新日志 / Changelog

## 0.7.1-beta (2026-11-22)

### 中文

- 默认物理预设从 hard 调到 default（软一档）：前保险杠 0.80、机盖 0.50、车身 0.70、车斗 0.90、后车身 0.80、尾门 0.70、车轮 1.00；hard 仍在设置页保留，想回到原来的硬度可以手动选。
- 打开物理配置锁：设置页恢复 default/soft/hard 三个预设和 7 个区域滑条，拖动即时应用到当前软壳并写入 `config/vnodo-client.properties`。
- 旧配置迁移：0.7.0 的配置文件没有 `profile_version`，且内容正是 hard 锁档时，首次加载会一次性切到新的 default；自定义过的旧配置保留，不会被强行覆盖。
- 包含 auditfix2 两条 P2：软-软车对碰撞前刷新对方未破三角形法线；读档先恢复前后压溃上限再夹塑料，尾部 0.46 m 不再回落到 0.34 m。
- 顺带修复 `BamngPlayerCollision` 日志参数多传，以及 `VnodoServerField.put` 空 UUID 的 NPE。
- 文档与版权：新增 `COPYRIGHT-NOTICE.txt`，README/LICENSE 明确要求再分发、整合包、镜像、分支和衍生版本不得删除版权说明文件与署名段落。

### English

- Default physics profile is one step softer, hard -> default: front bumper 0.80, hood 0.50, body 0.70, bed floor 0.90, rear body 0.80, tailgate 0.70, wheel 1.00. The hard preset remains selectable in the settings screen.
- The physics lock is open: the settings screen exposes the default/soft/hard presets and all seven region sliders again; edits apply to the live shell and persist to `config/vnodo-client.properties`.
- Existing 0.7.0 configs have no `profile_version`; a config that still matches the old hard-locked values migrates once to the new default. Hand-edited custom configs are preserved.
- Includes the two auditfix2 P2 fixes: foreign soft-body triangles are refreshed before the narrow phase, and saved front/rear crush limits are restored before plastic capping so a 0.46 m rear crush no longer falls back to 0.34 m.
- Also fixes the extra `BamngPlayerCollision` log argument and the null-UUID NPE in `VnodoServerField.put`.
- Docs & copyright: added `COPYRIGHT-NOTICE.txt`; README/LICENSE now state that redistributions, modpacks, mirrors, forks, and derivative versions must not remove the copyright notice or attribution paragraphs.

## 0.7.0-beta-hotfix3 (2026-11-22)

### 中文

- 修复"新车撞墙硬得像铁块"的根因：子步扫掠把节点停在碰撞盒外 0.1 毫米，而 `resolveBoxes()` 只把半径范围内的节点算作接触，节点于是一直悬在墙外、每次子步被重新夹回同一位置，一次接触都不写；没有墙接触就没有压溃窗口，梁保持满刚度。现在扫掠停在扩边盒内 5 毫米（1 微米的量会被第一步求解直接吃掉，仍然记不到接触），同一子步就记到接触并正常压溃。撞击判定本身没有放宽。
- 修复"车停了才轮到软体碰墙"：Vettura 的刚体壳先撞上墙并把车停住，软体网格接触时只剩 2–3 m/s 的残余速度，13 m/s 的撞击被当成 3 m/s，压溃深度只算出 5 厘米。现在把撞击前的真实到达速度沿墙法线送进接触，撞痕按 13 m/s 结算（目标 18.5 厘米），不再按残余爬行算。
- 修复"整辆车像钢筋"的另一半：撞击后推车头的冲量读的是 `this.prevSpeed`，而这个值在 step 之前已经被改成本帧的残余速度——13 m/s 撞完只剩 3 m/s，冲量只有 2.4 m/s，无头复现里塑性是 0.000，车头一点都不屈服。现在改用确认的到达速度（9.1 m/s），作用范围也从命中点 0.8 米扩到整个 1.1 米车头，不再只推 14 个节点。
- 存档升级到完整状态（v3）：梁的屈服上限、延伸上限、折叠进度、折叠次数全部随档保存。旧版只存了塑性位移和静息长度，退出时已经被压软的梁在读档后恢复满强度，同一辆车会一会儿软一会儿硬。
- 读档校形：旧存档里被拉爆的梁长会被校回合理区间（前部允许压到 0.1 米，车身/驾驶舱不低于原长 60%），节点塑性在读档瞬间就按新上限裁剪，不会先闪一帧尖刺。
- 修复玻璃"没撞也碎"：判定读的是节点位置与刚体目标之间的差值，这是弹簧滞后，不是损伤。正常刹车、转弯、过坎，驾驶舱上沿就能甩出 10–30 毫米，而这段代码每帧都在跑、也不要求本帧真撞到东西，所以车还在路上玻璃就碎了（日志里 front=0.011、plastic=0.000，一秒后才出现墙接触）。现在只在压溃窗口打开或本帧确有墙/碰撞盒接触时才判，并且要求同一块玻璃的边框至少两个节点同时越线，或者已有永久塑性；单个节点抖一下不再算。

### English

- Fixed the real cause of "a fresh truck hits a wall like an iron block": the substep sweep stopped nodes 0.1 mm outside the collision box, while `resolveBoxes()` only counts a contact inside the radius-expanded box.  The node hovered outside, the sweep re-clamped it to the same spot every substep and no contact was ever written; no wall contact means no crush window, so the beams kept full stiffness.  The sweep now stops 5 mm inside the expanded face (a one-micron overlap is eaten by the first solver correction and still registers nothing), so the same substep registers the contact and crushes normally.  No crash threshold was widened.
- Fixed the "hull stops before the soft shell arrives" case: Vettura resolves its rigid hull against the wall first and halts the truck, so the lattice only sees the 2-3 m/s leftover and a 13 m/s hit was sized as a 3 m/s tap (5 cm dent).  The pre-hit arrival speed is now fed into the contact along the wall normal, so the crush is sized from 13 m/s (18.5 cm target) instead of the crawl.
- Fixed the other half of "the whole truck feels like rebar": the front impulse was scaled by `this.prevSpeed`, which has already been overwritten with the tick's residual speed before the step.  A 13 m/s hit left 3 m/s, so the impulse was 2.4 m/s and the headless replay measured exactly 0.000 plastic.  It now uses the confirmed arrival speed (9.1 m/s) and covers the whole 1.1 m front clip instead of an 0.8 m ball around 14 nodes.
- Snapshot v3 stores the full beam yield plateau: max compression/tension stress, fold progress and fold count. Legacy saves only had plastic offset and rest length, so a parked wreck came back at full strength and felt soft or hard depending on the reload.
- Load sanitising: runaway rest lengths are clamped back (front may crush to 0.1 m, body/cab stay above 60 % of authored length) and node plastic is capped on the first frame.
- Fixed glass shattering with no impact: the check read the gap between a node's position and its rigid target, which is spring lag, not damage.  Normal braking, cornering and kerbs push the cab rim 10-30 mm, and the check ran every tick with no requirement that anything had been hit, so panes broke while simply driving (the log had front=0.011, plastic=0.000, with the first wall contact one second later).  It now runs only while the crush window is open or a wall/box contact happened this tick, and needs either permanent plastic or at least two nodes of the same pane frame past the limit.  One stray node no longer counts.

## 0.7.0-beta-hotfix2 (2026-11-22)

### 中文

- 修复车壳爆成黑色尖刺：上一版塑性率（0.60/0.70）让车身连续梁每一步都继续缩短，塑性位移一路堆到整车包围盒上限 3.1 m，网格被拉成尖刺。现在车身/驾驶舱塑性上限锁 0.30 m，通用塑性率回落到 0.42、前部 0.60、基础梁 0.40。
- 锚点失控保护：锚定节点被梁拖离目标超过 0.8 m 时直接归位，不再允许接近 2 m 的甩出。
- 玻璃改为逐块按自身变形触发：不再看整车凹陷，而是分别测前风挡、后窗、左窗、右窗各自区域的车壳节点偏移，任意一块区域超过 8 mm 就只碎那一块；前撞带弯后窗、侧撞碎对应侧窗，互不连坐。玻璃仍在服务端写入，存盘与联机同步不受影响。

### English

- Fixed the shell exploding into black spikes: the previous plasticity rates kept shortening body beams every step until plastic reached the 3.1 m vehicle envelope. Chassis plastic is now capped at 0.30 m; rates rolled back to 0.42 general, 0.60 front, 0.40 base.
- Anchor runaway guard: an anchored node dragged more than 0.8 m from its target snaps back instead of hanging near 2 m.
- Glass now shatters per pane from its own local deformation: the windscreen, rear window and each side window are measured separately over their own shell nodes, and any pane whose frame moves more than 8 mm breaks on its own.

## 0.7.0-beta-hotfix1 (2026-11-22)

### 中文

- 玻璃联动：新增软壳压溃阈值判定。前部面板一旦真实压溃（>8 mm）就通过服务端击碎前风挡；车身任一区域塑性深度超过阈值（面板 6 mm / 全车 18 mm）即四块玻璃同时炸开，解决"玻璃一点不碎"。
- 驾驶舱解硬：观察窗所在 cab 带从 95 kN/m 硬锚降到 30 kN/m 软锚，撞击时可以跟着塌 15–20%，不再整片纹丝不动。
- 塑性提升：通用梁塑性率 0.45→0.60，前部梁 0.45→0.80，基础梁默认 0.35→0.70，高速撞击不再"碰一下缩回去又撑起"。
- 残余应变烘焙：crash window 关闭的下降沿把锚定节点的当前偏移按 85% 写进永久塑性，现场就能得到"退出世界重进"之后那种真实形变，而不是等重载。
- 防穿地：每子步对高速节点做墙体扫掠夹取，单步位移超过 5 cm 时先扫掠再落地，避免一帧跨过一格墙、车头钻进墙基、引擎跑到路面以下。

### English

- Glass cascade driven by shell crush thresholds, applied through the server damage record so it persists and syncs.
- Cab band anchor softened (95 → 30 kN/m) so the observation-window area folds 15–20 % under impact.
- Higher plasticity (0.60 general, 0.80 front, 0.70 base beam) removes the spring-back feel.
- Falling edge of the crash window bakes 85 % of the anchored offset into permanent plastic, so the in-session result matches a world reload.
- Per-substep wall sweep for fast nodes stops one-block tunneling that put the nose inside a wall base and the engine under the road.

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
