# Third-Party Notices and References

This file records third-party code, tools, and feature references used by Vnodo. Only code that is actually copied, adapted, or distributed as a dependency is listed as “Included”. Projects that were only read for feature comparison are listed as “Reference only”.

## Current status

As of 2026-11-26, Vnodo does not include any third-party source code listed below. This file is the attribution template for the first MIT code that will be ported into the project. When code is actually included, the corresponding entry will be changed to “Included”, and the original copyright header will be kept in the source file.

## MIT code to be included

### Jolt Physics

- Project: https://github.com/jrouwe/JoltPhysics
- Copyright: Copyright 2021 Jorrit Rouwe
- License: MIT
- Use: collision, constraints, vehicle and soft-body backend (planned optional backend; not yet included).

### Jolt JNI

- Project: https://github.com/stephengold/jolt-jni
- Copyright: Copyright (c) 2013-2026 Stephen Gold
- License: MIT
- Use: JVM bindings for Jolt (planned dependency; not yet included).

### BeamNG Blender JBeam Editor

- Project: https://github.com/BeamNG/Blender-JBeam-Editor
- Copyright: Copyright (c) 2023 BeamNG GmbH, Angelo Matteo
- License: MIT
- Use: JBeam parsing, full-vehicle `.pc` import, slot merging, and node/beam/triangle editing (planned port of modules under `jbeam_editor/jbeam/`; not yet included).

### BlockPhysics

- Project: https://github.com/emortaldev/BlockPhysics
- Copyright: Copyright (c) 2023 emortaldev
- License: MIT
- Use: Minecraft-side Jolt initialization, object layers, ray casts, contact listeners, and block-world collision meshes (planned Forge adaptation; not yet included).

### FoxPhysic

- Project: https://github.com/Atimenka/FoxPhysic
- Copyright: Copyright (c) 2026 FoxPhys contributors
- License: MIT
- Use: XPBD soft bodies, self-collision, tearing, commands, public API, and block-density table (planned partial port; not yet included).

### BeamNGpy

- Project: https://github.com/BeamNG/BeamNGpy
- Copyright: Copyright (c) 2026 BeamNG GmbH
- License: MIT
- Use: automated crash tests and sensor tooling (requires BeamNG.tech; not yet included).

## MIT license text

The following text applies to the MIT projects above. Each project keeps its own copyright line.

```
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Reference only; no code copied

### Rigs of Rods

- Project: https://github.com/RigsOfRods/rigs-of-rods
- License: GPL-3.0
- Reference: node/beam construction, triangulation, `submesh` collision triangles, `contacters`, `set_collision_range`, `detacher`, `set_beam_defaults`, `loadweight/minimass`, flexbody hood/door/bumper deformation and detachment, and Beam Break Debug.
- Note: GPL-3.0 is not compatible with Vnodo's current CC BY-NC-SA 4.0 for direct code reuse. No code or documentation text was copied; the features are reimplemented from the general ideas.

### beamng-lan-multiplayer

- Project: https://github.com/L1ttleWizard/beamng-lan-multiplayer
- License: no license field provided by the repository
- Reference: 60 Hz vehicle state, damage delta sync, ghost mode, teleport, AI traffic, lights/wheel speed/RPM.
- Note: no code copied; only the sync frequency and threshold ideas are used.

### Sable

- Project: https://github.com/ryanhcode/sable
- License: Polyform Shield 1.0.0
- Reference: sub-level moving-structure concept.
- Note: noncompete restrictions; no code copied.

### Valkyrien Skies 2

- Project: https://github.com/ValkyrienSkies/Valkyrien-Skies-2
- License: LGPL-3.0
- Reference: moving-structure collision, network sync, addon API.
- Note: copying code would trigger LGPL obligations; no code copied.

## Maintenance rules

1. When MIT code is added, keep the original copyright header in the source file and update this file.
2. When a dependency is added, record group/artifact/version and license.
3. Do not describe reference-only projects as “code included”.
4. Stop and review the license boundary before adding GPL, LGPL, Polyform Shield, or unlicensed code.
