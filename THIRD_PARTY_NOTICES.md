# Third-party notices

## Notable adapted portions

Index of the portions adapted from other projects; each section below carries the attribution
in full.

- `chesttracker/` portions - [Entity Tracker](https://github.com/liangforstudy/entity-tracker) by liangforstudy — MIT (© liangforstudy)
- Enemy tab and `.enemy` - [MeteorEnemies](https://github.com/crosby-moe/MeteorEnemies) by Crosby — MIT (© Crosby) (reference)
- `modules/AutoFarm.java` portions - [Meteor Rejects](https://github.com/AntiCope/meteor-rejects) by AntiCope — GPL-3.0
- `modules/B36.java`, `modules/AutoMoss.java` portions — [hybridious_mod](https://github.com/Hybridious/hybridious_mod) by Hybridious — AGPL-3.0 (
  see [third-party/licenses/LICENSE-AGPL-3.0.txt](third-party/licenses/LICENSE-AGPL-3.0.txt)); combined under GPL-3.0 per AGPL-3.0 section 13
- `modules/InventoryInfo.java` and its support files — [PathSeeker](https://github.com/FaxHack/PathSeeker) by FaxHack (Apache-2.0), a Meteor port
  of [shulker-view](https://github.com/cattyngmd/shulker-view) by cattyn (MIT, © 2024 cattyn) (
  see [third-party/licenses/LICENSE-Apache-2.0.txt](third-party/licenses/LICENSE-Apache-2.0.txt)
  and [third-party/licenses/LICENSE-MIT.txt](third-party/licenses/LICENSE-MIT.txt))

## Entity Tracker (Chest Tracker port)

Parts of `src/main/java/com/AutoBookshelf/addon/modules/chesttracker/` are ported from
**Entity Tracker** by liangforstudy (<https://github.com/liangforstudy/entity-tracker>),
which is licensed under the MIT License:

```
MIT License

Copyright (c) liangforstudy

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

Ported files carry two header lines directly above the package declaration:

```
// SPDX-License-Identifier: GPL-3.0-only AND MIT
// Ported from liangforstudy's Entity Tracker (MIT). See THIRD_PARTY_NOTICES.md.
```

The 40 ported files (all of `src/main/java/com/AutoBookshelf/addon/modules/chesttracker/`
**except** the four listed below) are:

- `modules/chesttracker/Settings.java`
- `modules/chesttracker/data/BankStorage.java`, `BlockRecord.java`, `BookStore.java`,
  `ChestTrackerImport.java`, `ContainerRecord.java`, `EntityRecord.java`, `InventoryRecord.java`,
  `ItemData.java`, `LegacyImport.java`, `MemoryBank.java`, `Notes.java`, `RecordStatus.java`,
  `StackStore.java`, `TradeData.java`
- `modules/chesttracker/gui/AreaConfirmScreen.java`, `ButtonBar.java`, `CloseKey.java`,
  `DetailScreen.java`, `Dimensions.java`, `Messages.java`, `ScreenTabs.java`, `TrackerScreen.java`,
  `TradeDetailScreen.java`, `TradeView.java`, `TradesScreen.java`
- `modules/chesttracker/search/Fuzzy.java`, `HeadAliases.java`, `HeadInfo.java`, `ItemIndex.java`,
  `ItemQuery.java`, `Tally.java`, `TallyReport.java`
- `modules/chesttracker/track/BankManager.java`, `DisplayTracker.java`, `InteractionTracker.java`,
  `PositionTracker.java`, `ScreenBinder.java`, `TradeBinder.java`, `Trash.java`
- `mixin/accessor/MountScreenHandlerAccessor.java` (also ported from Entity Tracker)

The rest of the chesttracker tree (`chesttracker/ChestTrackerModule.java`,
`chesttracker/TrackerConfig.java`, `chesttracker/TrackerLog.java`,
`chesttracker/TrackerServices.java`) is original to this addon and licensed under GPL-3.0.
The MIT text is reproduced above; the rest of this addon is licensed under GPL-3.0; the
MIT-licensed portions remain available under MIT.

## MeteorEnemies (Enemy tab reference)

The Enemy tab and `.enemy` command were built with
**MeteorEnemies** by Crosby (<https://github.com/crosby-moe/MeteorEnemies>)
as a reference. MeteorEnemies is licensed under the MIT License:

```
MIT License

Copyright (c) 2025 Crosby

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

The rest of this addon is licensed under GPL-3.0; the MIT-licensed portions remain available under MIT.

## quiettee-utils (Lance / BoatPhase / BoatShot port)

Parts of `src/main/java/com/AutoBookshelf/addon/modules/combat/LOSAT/` (Lance, LanceWebMath,
LanceAttackMath, LanceApproachPriority, LanceBeatTiming, LanceCouchTiming, LanceCrystalSafety,
LanceLanePolicy, LanceMovementMath, LancePathSafety, LanceWebTiming, BlockBreaker,
ExplosionReductions), `src/main/java/com/AutoBookshelf/addon/modules/movement/` (BoatPhase and
its helpers), `src/main/java/com/AutoBookshelf/addon/modules/combat/AttackAircraft/` (BoatShot and
the LanceWebMath-derived BoatShotWeb code), `src/main/java/com/AutoBookshelf/addon/mixin/accessor/LivingEntityAccessor.java`
and `src/main/java/com/AutoBookshelf/addon/mixin/BoatShotInputMixin.java` are ported or
adapted from **quiettee-utils** by shyalboi (<https://github.com/shyalboi/quiettee-utilss>),
which is licensed under the GNU General Public License, version 3. The ported portions retain
their original copyright; the modifications in this addon remain governed by GPL-3.0. See
[LICENSE](LICENSE). No coord leaks or malicious code were found on review (the debug-log writer
is gated behind `fileDebug` and only writes to `mc.gameDirectory/lance-debug/lance-*.log`; the
`chatDebug` telemetry prints target name plus stats, never coordinates).

## hybridious_mod (B36 / AutoMoss adaptation)

Parts of `src/main/java/com/AutoBookshelf/addon/modules/B36.java` and
`src/main/java/com/AutoBookshelf/addon/modules/AutoMoss.java` are adapted from
**hybridious_mod** by Hybridious (<https://github.com/Hybridious/hybridious_mod>),
which is licensed under the GNU Affero General Public License, version 3. The adapted
portions have been modified substantially; the copyright of the original code remains with
Hybridious. Distributed as a single combined work with the rest of this addon per section
13 of the AGPL-3.0; the adapted portions remain governed by the AGPL-3.0 (see
[third-party/licenses/LICENSE-AGPL-3.0.txt](third-party/licenses/LICENSE-AGPL-3.0.txt)), and the remaining parts of this addon remain
governed by the GPL-3.0 as before. Hybridious's original copyright notices, as present in
the upstream files, are retained in those source files.

## shulker-view / PathSeeker (InventoryInfo adaptation)

The InventoryInfo code is adapted from **two chained upstreams**, not one:

1. **shulker-view** by cattyn (<https://github.com/cattyngmd/shulker-view>), an MIT-licensed
   Fabric mod that draws shulker item previews on screens. Copyright (c) 2024 cattyn.
   The MIT License text is included in full below and reproduced in
   [third-party/licenses/LICENSE-MIT.txt](third-party/licenses/LICENSE-MIT.txt).
2. **PathSeeker** by FaxHack (<https://github.com/FaxHack/PathSeeker>), which ported the
   shulker-view design to Meteor Client (`modules/render/InventoryInfoModule.java`,
   `utils/ShulkerInfo.java`, `utils/Type.java`, `events/ScreenRenderEvent.java`,
   `mixin/MixinHandledScreen.java`). PathSeeker is licensed under the Apache License, version 2.0.
   See [third-party/licenses/LICENSE-Apache-2.0.txt](third-party/licenses/LICENSE-Apache-2.0.txt).

The adapted portions in this addon (`modules/InventoryInfo.java`, `utils/ShulkerInfo.java`,
`utils/Type.java`, `events/ScreenRenderEvent.java`, `mixin/MixinHandledScreen.java`) are
modified substantially from both, and the copyright of the upstream code remains with their
respective authors (cattyn and FaxHack). Cattyn's MIT attribution is preserved as required by
the MIT License; FaxHack's Apache-2.0 attribution is preserved as required by the Apache License.
The remaining parts of this addon are licensed under GPL-3.0; the MIT-licensed portions remain
available under MIT and the Apache-2.0 portions under Apache-2.0.

The MIT text (shulker-view) is reproduced verbatim here:

```
Copyright (c) 2024 cattyn

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## Meteor Rejects (AutoFarm adaptation)

Parts of `src/main/java/com/AutoBookshelf/addon/modules/AutoFarm.java` are adapted from
**Meteor Rejects** by AntiCope (<https://github.com/AntiCope/meteor-rejects>), which is
licensed under the GNU General Public License, version 3. Meteor Rejects is a Meteor
Client addon, so this remains compatible with this addon's GPL-3.0 license. See the
header comment in that file for authorship details.

Contributors may reuse the standalone MIT portions under the terms of the MIT License. The rest
of the addon remains GPL-3.0; see this file for the attribution we keep.
