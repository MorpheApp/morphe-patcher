## [1.5.2](https://github.com/MorpheApp/morphe-patcher/compare/v1.5.1...v1.5.2) (2026-06-10)


### Bug Fixes

* Change Compatibility app name to required if package name is also declared ([f68e855](https://github.com/MorpheApp/morphe-patcher/commit/f68e855cbe83733fdf4d01c974b6f8c14e778939))
* Deprecate `MatchAfterAtLeast` and `MatchAfterRange` ([172a8aa](https://github.com/MorpheApp/morphe-patcher/commit/172a8aa7882e69ab9ee288a0c10cac8f83dd9117))
* Update to latest ARSCLib ([d30cf05](https://github.com/MorpheApp/morphe-patcher/commit/d30cf05e5b638608edb3e07a5f6e834a242cb26c))

## [1.5.2-dev.3](https://github.com/MorpheApp/morphe-patcher/compare/v1.5.2-dev.2...v1.5.2-dev.3) (2026-06-10)


### Bug Fixes

* Change Compatibility app name to required if package name is also declared ([f68e855](https://github.com/MorpheApp/morphe-patcher/commit/f68e855cbe83733fdf4d01c974b6f8c14e778939))

## [1.5.2-dev.2](https://github.com/MorpheApp/morphe-patcher/compare/v1.5.2-dev.1...v1.5.2-dev.2) (2026-06-07)


### Bug Fixes

* Deprecate `MatchAfterAtLeast` and `MatchAfterRange` ([172a8aa](https://github.com/MorpheApp/morphe-patcher/commit/172a8aa7882e69ab9ee288a0c10cac8f83dd9117))

## [1.5.2-dev.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.5.1...v1.5.2-dev.1) (2026-05-31)


### Bug Fixes

* Update to latest ARSCLib ([d30cf05](https://github.com/MorpheApp/morphe-patcher/commit/d30cf05e5b638608edb3e07a5f6e834a242cb26c))

## [1.5.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.5.0...v1.5.1) (2026-05-11)


### Bug Fixes

* Change to fixed upstream ARSCLib ([4b3add9](https://github.com/MorpheApp/morphe-patcher/commit/4b3add92fc64d64ea369a4838cdb559fe0cc44ad))
* Handle fingerprint matchAll() when a method contains multiple string matches ([46e06ac](https://github.com/MorpheApp/morphe-patcher/commit/46e06acc48babad478462e652781a8c0aef45c8c))
* Restore backwards compatibility for previously compiled code ([c2536be](https://github.com/MorpheApp/morphe-patcher/commit/c2536be831f7a8542c4e9eb8eae49f3513e1e426))
* Try all file extensions during dex verification ([16bcfdf](https://github.com/MorpheApp/morphe-patcher/commit/16bcfdf5394c371751ab942d1e0806814206af52))

## [1.5.1-dev.4](https://github.com/MorpheApp/morphe-patcher/compare/v1.5.1-dev.3...v1.5.1-dev.4) (2026-05-10)


### Bug Fixes

* Handle fingerprint matchAll() when a method contains multiple string matches ([46e06ac](https://github.com/MorpheApp/morphe-patcher/commit/46e06acc48babad478462e652781a8c0aef45c8c))

## [1.5.1-dev.3](https://github.com/MorpheApp/morphe-patcher/compare/v1.5.1-dev.2...v1.5.1-dev.3) (2026-05-09)


### Bug Fixes

* Restore backwards compatibility for previously compiled code ([c2536be](https://github.com/MorpheApp/morphe-patcher/commit/c2536be831f7a8542c4e9eb8eae49f3513e1e426))

## [1.5.1-dev.2](https://github.com/MorpheApp/morphe-patcher/compare/v1.5.1-dev.1...v1.5.1-dev.2) (2026-05-03)


### Bug Fixes

* Try all file extensions during dex verification ([16bcfdf](https://github.com/MorpheApp/morphe-patcher/commit/16bcfdf5394c371751ab942d1e0806814206af52))

## [1.5.1-dev.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.5.0...v1.5.1-dev.1) (2026-05-02)


### Bug Fixes

* Change to fixed upstream ARSCLib ([4b3add9](https://github.com/MorpheApp/morphe-patcher/commit/4b3add92fc64d64ea369a4838cdb559fe0cc44ad))

# [1.5.0](https://github.com/MorpheApp/morphe-patcher/compare/v1.4.2...v1.5.0) (2026-04-26)


### Features

* `AppTarget` `versionCodes` field to restrict patching to specific version code releases ([#120](https://github.com/MorpheApp/morphe-patcher/issues/120)) ([393fc8f](https://github.com/MorpheApp/morphe-patcher/commit/393fc8f81b9b1ce307bd68577f8b80d2ea9402c6))
* `Compatibility` `including()` and `excluding()` methods ([#122](https://github.com/MorpheApp/morphe-patcher/issues/122)) ([1e0f468](https://github.com/MorpheApp/morphe-patcher/commit/1e0f468db2f834af87b3cfecb30f5ec7723e614b))
* Instruction match helper methods to get a called method or accessed field ([#121](https://github.com/MorpheApp/morphe-patcher/issues/121)) ([4908e7b](https://github.com/MorpheApp/morphe-patcher/commit/4908e7bd97e7e4155f72cec749d0d16a76d554f7))

# [1.5.0-dev.3](https://github.com/MorpheApp/morphe-patcher/compare/v1.5.0-dev.2...v1.5.0-dev.3) (2026-04-24)


### Features

* `Compatibility` `including()` and `excluding()` methods ([#122](https://github.com/MorpheApp/morphe-patcher/issues/122)) ([1e0f468](https://github.com/MorpheApp/morphe-patcher/commit/1e0f468db2f834af87b3cfecb30f5ec7723e614b))

# [1.5.0-dev.2](https://github.com/MorpheApp/morphe-patcher/compare/v1.5.0-dev.1...v1.5.0-dev.2) (2026-04-23)


### Features

* Instruction match helper methods to get a called method or accessed field ([#121](https://github.com/MorpheApp/morphe-patcher/issues/121)) ([4908e7b](https://github.com/MorpheApp/morphe-patcher/commit/4908e7bd97e7e4155f72cec749d0d16a76d554f7))

# [1.5.0-dev.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.4.2...v1.5.0-dev.1) (2026-04-23)


### Features

* `AppTarget` `versionCodes` field to restrict patching to specific version code releases ([#120](https://github.com/MorpheApp/morphe-patcher/issues/120)) ([393fc8f](https://github.com/MorpheApp/morphe-patcher/commit/393fc8f81b9b1ce307bd68577f8b80d2ea9402c6))

## [1.4.2](https://github.com/MorpheApp/morphe-patcher/compare/v1.4.1...v1.4.2) (2026-04-20)


### Bug Fixes

* Do not allow universal patches to be default on ([12a4fc1](https://github.com/MorpheApp/morphe-patcher/commit/12a4fc125634face6fe1b571178456fd3552ff3e))
* Return defensive copy from `ArsclibResourceCoder.getDeletedFiles` ([#119](https://github.com/MorpheApp/morphe-patcher/issues/119)) ([b0e2dc8](https://github.com/MorpheApp/morphe-patcher/commit/b0e2dc872cdb091db546ea8c450ddeb127badd7a))
* Update to Android tools 9.1.1 ([9779d9b](https://github.com/MorpheApp/morphe-patcher/commit/9779d9bcee0fb01dfd7bc44594936026e54da04c))

## [1.4.2-dev.3](https://github.com/MorpheApp/morphe-patcher/compare/v1.4.2-dev.2...v1.4.2-dev.3) (2026-04-20)


### Bug Fixes

* Return defensive copy from `ArsclibResourceCoder.getDeletedFiles` ([#119](https://github.com/MorpheApp/morphe-patcher/issues/119)) ([b0e2dc8](https://github.com/MorpheApp/morphe-patcher/commit/b0e2dc872cdb091db546ea8c450ddeb127badd7a))

## [1.4.2-dev.2](https://github.com/MorpheApp/morphe-patcher/compare/v1.4.2-dev.1...v1.4.2-dev.2) (2026-04-19)


### Bug Fixes

* Update to Android tools 9.1.1 ([9779d9b](https://github.com/MorpheApp/morphe-patcher/commit/9779d9bcee0fb01dfd7bc44594936026e54da04c))

## [1.4.2-dev.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.4.1...v1.4.2-dev.1) (2026-04-19)


### Bug Fixes

* Do not allow universal patches to be default on ([12a4fc1](https://github.com/MorpheApp/morphe-patcher/commit/12a4fc125634face6fe1b571178456fd3552ff3e))

## [1.4.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.4.0...v1.4.1) (2026-04-18)


### Bug Fixes

* Bump smali version ([7c82594](https://github.com/MorpheApp/morphe-patcher/commit/7c82594bec04426a85e4147f71f9f76cb19b8272))

## [1.4.1-dev.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.4.0...v1.4.1-dev.1) (2026-04-18)


### Bug Fixes

* Bump smali version ([7c82594](https://github.com/MorpheApp/morphe-patcher/commit/7c82594bec04426a85e4147f71f9f76cb19b8272))

# [1.4.0](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.3...v1.4.0) (2026-04-17)


### Bug Fixes

* Separate out desugar libs into separate dex ([7fc7a9e](https://github.com/MorpheApp/morphe-patcher/commit/7fc7a9e8788e9167756edc596c4aeb85972e1fc3))
* Track deleted files in detectFileChanges so getDeletedFiles reports them   ([#107](https://github.com/MorpheApp/morphe-patcher/issues/107)) ([6264104](https://github.com/MorpheApp/morphe-patcher/commit/6264104ba233101412cac4c801c4f64138b53763))
* Write modified dex files to final APK when no resource patches are used ([#109](https://github.com/MorpheApp/morphe-patcher/issues/109)) ([0f401c6](https://github.com/MorpheApp/morphe-patcher/commit/0f401c6445655a5ee27f59c67d035c119f54446e))


### Features

* Add `matchAll(range)` to validate the number of matches ([#104](https://github.com/MorpheApp/morphe-patcher/issues/104)) ([b28d21a](https://github.com/MorpheApp/morphe-patcher/commit/b28d21aadd8f6f4505a19885acb2fb399b2f882c))
* Add DEX/APK verification ([#110](https://github.com/MorpheApp/morphe-patcher/issues/110)) ([61b5de0](https://github.com/MorpheApp/morphe-patcher/commit/61b5de0dd073139bb8ad8999a6c16b85d411e009))
* Map dex files in memory to reduce memory requirements ([#106](https://github.com/MorpheApp/morphe-patcher/issues/106)) ([85df75e](https://github.com/MorpheApp/morphe-patcher/commit/85df75ef8b4614f5cf89d8ba1700335559cb0c9b))
* Optimize dex patching ([#105](https://github.com/MorpheApp/morphe-patcher/issues/105)) ([4e70edb](https://github.com/MorpheApp/morphe-patcher/commit/4e70edb7d3e0568dd32cddb10a55ddfbc13edb05))

# [1.4.0-dev.7](https://github.com/MorpheApp/morphe-patcher/compare/v1.4.0-dev.6...v1.4.0-dev.7) (2026-04-17)


### Bug Fixes

* Separate out desugar libs into separate dex ([7fc7a9e](https://github.com/MorpheApp/morphe-patcher/commit/7fc7a9e8788e9167756edc596c4aeb85972e1fc3))

# [1.4.0-dev.6](https://github.com/MorpheApp/morphe-patcher/compare/v1.4.0-dev.5...v1.4.0-dev.6) (2026-04-16)


### Features

* Add DEX/APK verification ([#110](https://github.com/MorpheApp/morphe-patcher/issues/110)) ([61b5de0](https://github.com/MorpheApp/morphe-patcher/commit/61b5de0dd073139bb8ad8999a6c16b85d411e009))

# [1.4.0-dev.5](https://github.com/MorpheApp/morphe-patcher/compare/v1.4.0-dev.4...v1.4.0-dev.5) (2026-04-14)


### Bug Fixes

* Track deleted files in detectFileChanges so getDeletedFiles reports them   ([#107](https://github.com/MorpheApp/morphe-patcher/issues/107)) ([6264104](https://github.com/MorpheApp/morphe-patcher/commit/6264104ba233101412cac4c801c4f64138b53763))

# [1.4.0-dev.4](https://github.com/MorpheApp/morphe-patcher/compare/v1.4.0-dev.3...v1.4.0-dev.4) (2026-04-13)


### Bug Fixes

* Write modified dex files to final APK when no resource patches are used ([#109](https://github.com/MorpheApp/morphe-patcher/issues/109)) ([0f401c6](https://github.com/MorpheApp/morphe-patcher/commit/0f401c6445655a5ee27f59c67d035c119f54446e))

# [1.4.0-dev.3](https://github.com/MorpheApp/morphe-patcher/compare/v1.4.0-dev.2...v1.4.0-dev.3) (2026-04-07)


### Features

* Map dex files in memory to reduce memory requirements ([#106](https://github.com/MorpheApp/morphe-patcher/issues/106)) ([85df75e](https://github.com/MorpheApp/morphe-patcher/commit/85df75ef8b4614f5cf89d8ba1700335559cb0c9b))

# [1.4.0-dev.2](https://github.com/MorpheApp/morphe-patcher/compare/v1.4.0-dev.1...v1.4.0-dev.2) (2026-04-06)


### Features

* Optimize dex patching ([#105](https://github.com/MorpheApp/morphe-patcher/issues/105)) ([4e70edb](https://github.com/MorpheApp/morphe-patcher/commit/4e70edb7d3e0568dd32cddb10a55ddfbc13edb05))

# [1.4.0-dev.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.3...v1.4.0-dev.1) (2026-04-05)


### Features

* Add `matchAll(range)` to validate the number of matches ([#104](https://github.com/MorpheApp/morphe-patcher/issues/104)) ([b28d21a](https://github.com/MorpheApp/morphe-patcher/commit/b28d21aadd8f6f4505a19885acb2fb399b2f882c))

## [1.3.3](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.2...v1.3.3) (2026-03-28)


### Bug Fixes

* Add Compatibility convenience constructor for patches with 'any' app target ([5969af5](https://github.com/MorpheApp/morphe-patcher/commit/5969af542ea5a8ba9a47541dce51fa2ae03ac2e2))
* Handle matchAll when a class fingerprint exists ([107f168](https://github.com/MorpheApp/morphe-patcher/commit/107f16825d61adb79c7f42206d4444d5929845a2))

## [1.3.3-dev.2](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.3-dev.1...v1.3.3-dev.2) (2026-03-24)


### Bug Fixes

* Handle matchAll when a class fingerprint exists ([107f168](https://github.com/MorpheApp/morphe-patcher/commit/107f16825d61adb79c7f42206d4444d5929845a2))

## [1.3.3-dev.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.2...v1.3.3-dev.1) (2026-03-23)


### Bug Fixes

* Add Compatibility convenience constructor for patches with 'any' app target ([5969af5](https://github.com/MorpheApp/morphe-patcher/commit/5969af542ea5a8ba9a47541dce51fa2ae03ac2e2))

## [1.3.2](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.1...v1.3.2) (2026-03-22)


### Bug Fixes

* Restore binary backwards compatibility ([#91](https://github.com/MorpheApp/morphe-patcher/issues/91)) ([a556413](https://github.com/MorpheApp/morphe-patcher/commit/a556413455539d534f129430a5145d6c62756884))

## [1.3.2-dev.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.1...v1.3.2-dev.1) (2026-03-22)


### Bug Fixes

* Restore binary backwards compatibility ([#91](https://github.com/MorpheApp/morphe-patcher/issues/91)) ([a556413](https://github.com/MorpheApp/morphe-patcher/commit/a556413455539d534f129430a5145d6c62756884))

## [1.3.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.0...v1.3.1) (2026-03-22)


### Bug Fixes

* Remove overloaded deprecated patch 'use' constructor ([#89](https://github.com/MorpheApp/morphe-patcher/issues/89)) ([c4db0ca](https://github.com/MorpheApp/morphe-patcher/commit/c4db0cac9ecbaced8b25e4443db6a19a61830e2f))

## [1.3.1-dev.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.0...v1.3.1-dev.1) (2026-03-22)


### Bug Fixes

* Remove overloaded deprecated patch 'use' constructor ([#89](https://github.com/MorpheApp/morphe-patcher/issues/89)) ([c4db0ca](https://github.com/MorpheApp/morphe-patcher/commit/c4db0cac9ecbaced8b25e4443db6a19a61830e2f))

# [1.3.0](https://github.com/MorpheApp/morphe-patcher/compare/v1.2.0...v1.3.0) (2026-03-22)


### Bug Fixes

* Changes to resources in RAW_ONLY mode were not being picked up ([#78](https://github.com/MorpheApp/morphe-patcher/issues/78)) ([5dc1c48](https://github.com/MorpheApp/morphe-patcher/commit/5dc1c484c7d134fac7b95912dd842549c63149a7))
* RAW_ONLY handling on Windows, uncompressed files ([#79](https://github.com/MorpheApp/morphe-patcher/issues/79)) ([c25e5f8](https://github.com/MorpheApp/morphe-patcher/commit/c25e5f832e24854805758168359a0ec9c432f9ae))
* Set extractNativeLibs to value in manifest by default ([#88](https://github.com/MorpheApp/morphe-patcher/issues/88)) ([5944eef](https://github.com/MorpheApp/morphe-patcher/commit/5944eef0ad7d152f2286fff862ec456ded2bdaad))
* Speed up slow resource decoding for APKs with duplicate spec strings ([#75](https://github.com/MorpheApp/morphe-patcher/issues/75)) ([6eff16e](https://github.com/MorpheApp/morphe-patcher/commit/6eff16eda5b247b6c3b5bf63f62489e4f1e89268))
* Wire keepArchitectures to resource coders ([d3519f8](https://github.com/MorpheApp/morphe-patcher/commit/d3519f8c305fe9327044c4cd37bd4e80360a5356))


### Features

* Add `classFingerprint` field to Fingerprint ([#71](https://github.com/MorpheApp/morphe-patcher/issues/71)) ([c808b09](https://github.com/MorpheApp/morphe-patcher/commit/c808b0912e6d889bea8a315936b2ea62ff284ca6))
* Add support for stripping native libraries ([#83](https://github.com/MorpheApp/morphe-patcher/issues/83)) ([bc629ed](https://github.com/MorpheApp/morphe-patcher/commit/bc629ed5159bf1d8b4c774870bf1103fe4f0996e))
* Expand Compatibility declaration ([#73](https://github.com/MorpheApp/morphe-patcher/issues/73)) ([ed99716](https://github.com/MorpheApp/morphe-patcher/commit/ed99716309e96ceef1817411314a092601691560))
* Fingerprint matchAll() functionality ([#87](https://github.com/MorpheApp/morphe-patcher/issues/87)) ([7660630](https://github.com/MorpheApp/morphe-patcher/commit/7660630964de43a0d2f590b9e4f98b347325e8be))

# [1.3.0-dev.10](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.0-dev.9...v1.3.0-dev.10) (2026-03-21)


### Bug Fixes

* Set extractNativeLibs to value in manifest by default ([#88](https://github.com/MorpheApp/morphe-patcher/issues/88)) ([5944eef](https://github.com/MorpheApp/morphe-patcher/commit/5944eef0ad7d152f2286fff862ec456ded2bdaad))

# [1.3.0-dev.9](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.0-dev.8...v1.3.0-dev.9) (2026-03-20)

# [1.3.0-dev.8](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.0-dev.7...v1.3.0-dev.8) (2026-03-20)


### Features

* Fingerprint matchAll() functionality ([#87](https://github.com/MorpheApp/morphe-patcher/issues/87)) ([7660630](https://github.com/MorpheApp/morphe-patcher/commit/7660630964de43a0d2f590b9e4f98b347325e8be))

# [1.3.0-dev.7](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.0-dev.6...v1.3.0-dev.7) (2026-03-19)


### Bug Fixes

* Wire keepArchitectures to resource coders ([d3519f8](https://github.com/MorpheApp/morphe-patcher/commit/d3519f8c305fe9327044c4cd37bd4e80360a5356))

# [1.3.0-dev.6](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.0-dev.5...v1.3.0-dev.6) (2026-03-19)


### Features

* Add support for stripping native libraries ([#83](https://github.com/MorpheApp/morphe-patcher/issues/83)) ([bc629ed](https://github.com/MorpheApp/morphe-patcher/commit/bc629ed5159bf1d8b4c774870bf1103fe4f0996e))

# [1.3.0-dev.5](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.0-dev.4...v1.3.0-dev.5) (2026-03-16)


### Bug Fixes

* RAW_ONLY handling on Windows, uncompressed files ([#79](https://github.com/MorpheApp/morphe-patcher/issues/79)) ([c25e5f8](https://github.com/MorpheApp/morphe-patcher/commit/c25e5f832e24854805758168359a0ec9c432f9ae))

# [1.3.0-dev.4](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.0-dev.3...v1.3.0-dev.4) (2026-03-15)


### Features

* Expand Compatibility declaration ([#73](https://github.com/MorpheApp/morphe-patcher/issues/73)) ([ed99716](https://github.com/MorpheApp/morphe-patcher/commit/ed99716309e96ceef1817411314a092601691560))

# [1.3.0-dev.3](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.0-dev.2...v1.3.0-dev.3) (2026-03-15)


### Bug Fixes

* Changes to resources in RAW_ONLY mode were not being picked up ([#78](https://github.com/MorpheApp/morphe-patcher/issues/78)) ([5dc1c48](https://github.com/MorpheApp/morphe-patcher/commit/5dc1c484c7d134fac7b95912dd842549c63149a7))

# [1.3.0-dev.2](https://github.com/MorpheApp/morphe-patcher/compare/v1.3.0-dev.1...v1.3.0-dev.2) (2026-03-13)


### Bug Fixes

* Speed up slow resource decoding for APKs with duplicate spec strings ([#75](https://github.com/MorpheApp/morphe-patcher/issues/75)) ([6eff16e](https://github.com/MorpheApp/morphe-patcher/commit/6eff16eda5b247b6c3b5bf63f62489e4f1e89268))

# [1.3.0-dev.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.2.0...v1.3.0-dev.1) (2026-03-10)


### Features

* Add `classFingerprint` field to Fingerprint ([#71](https://github.com/MorpheApp/morphe-patcher/issues/71)) ([c808b09](https://github.com/MorpheApp/morphe-patcher/commit/c808b0912e6d889bea8a315936b2ea62ff284ca6))

# [1.2.0](https://github.com/MorpheApp/morphe-patcher/compare/v1.1.1...v1.2.0) (2026-03-07)


### Bug Fixes

* Add missing XML namespaces when renaming package ([#65](https://github.com/MorpheApp/morphe-patcher/issues/65)) ([6568009](https://github.com/MorpheApp/morphe-patcher/commit/6568009327c2d106ca8c194adbd473623abc03e4))
* Add some extension methods from morphe-library ([c2b667b](https://github.com/MorpheApp/morphe-patcher/commit/c2b667b2eb249598260f721924e2bbd7ed20dca4))
* additional use of streaming XML parser to speed up XML processing ([#66](https://github.com/MorpheApp/morphe-patcher/issues/66)) ([b2d45df](https://github.com/MorpheApp/morphe-patcher/commit/b2d45dffab5f903556e16179bdc63a381cd6d363))
* Expand theme attribute references during XML processing ([#69](https://github.com/MorpheApp/morphe-patcher/issues/69)) ([9a2f1ff](https://github.com/MorpheApp/morphe-patcher/commit/9a2f1ffce7c0d34f701104f7227273ff28edf492))
* process generic item XML tags, only process modified XMLs instead of all XMLs ([#67](https://github.com/MorpheApp/morphe-patcher/issues/67)) ([91e9624](https://github.com/MorpheApp/morphe-patcher/commit/91e962487dedf73104e23a71b82141d31c21d15d))
* Sanitize invalid XML characters in unpatched apk ([#54](https://github.com/MorpheApp/morphe-patcher/issues/54)) ([86a4087](https://github.com/MorpheApp/morphe-patcher/commit/86a4087c6345b5150ffa43e3695eba9e96224070))
* Sanitize strings.xml after decoding resources ([#55](https://github.com/MorpheApp/morphe-patcher/issues/55)) ([4155343](https://github.com/MorpheApp/morphe-patcher/commit/41553432f784dda34ddc3c2364279c6d2d87c3df))
* Specify UTF-8 during XML processing to prevent encoding issues on Windows ([#58](https://github.com/MorpheApp/morphe-patcher/issues/58)) ([842604d](https://github.com/MorpheApp/morphe-patcher/commit/842604d0504c904ac2abd6f2b268c164388384fc))
* Use streaming XML processing ([#56](https://github.com/MorpheApp/morphe-patcher/issues/56)) ([e9d56d0](https://github.com/MorpheApp/morphe-patcher/commit/e9d56d0a1f06e04a72ff031974c894bd113985f5))


### Features

* Add a version code field to `PackageMetadata` ([#47](https://github.com/MorpheApp/morphe-patcher/issues/47)) ([c8800fd](https://github.com/MorpheApp/morphe-patcher/commit/c8800fd725a32009542e45743dc533b73f97d747))
* Add public packageMetadata to `ResourcePatchContext` ([b2e7df8](https://github.com/MorpheApp/morphe-patcher/commit/b2e7df87c5b40a73c654e9d618d84d6d6ede77ac))
* Decouple morphe-library and morphe-patcher ([#60](https://github.com/MorpheApp/morphe-patcher/issues/60)) ([1785631](https://github.com/MorpheApp/morphe-patcher/commit/1785631d74e32475e6128713ab9c51d3a33645e1))
* Use arsclib during resource encoding/decoding and fix memory leaks ([#48](https://github.com/MorpheApp/morphe-patcher/issues/48)) ([7d0f837](https://github.com/MorpheApp/morphe-patcher/commit/7d0f837c939df44b78d81fff6c4f100c6afada49))

# [1.2.0-dev.13](https://github.com/MorpheApp/morphe-patcher/compare/v1.2.0-dev.12...v1.2.0-dev.13) (2026-03-05)


### Bug Fixes

* Expand theme attribute references during XML processing ([#69](https://github.com/MorpheApp/morphe-patcher/issues/69)) ([9a2f1ff](https://github.com/MorpheApp/morphe-patcher/commit/9a2f1ffce7c0d34f701104f7227273ff28edf492))

# [1.2.0-dev.12](https://github.com/MorpheApp/morphe-patcher/compare/v1.2.0-dev.11...v1.2.0-dev.12) (2026-03-05)


### Bug Fixes

* process generic item XML tags, only process modified XMLs instead of all XMLs ([#67](https://github.com/MorpheApp/morphe-patcher/issues/67)) ([91e9624](https://github.com/MorpheApp/morphe-patcher/commit/91e962487dedf73104e23a71b82141d31c21d15d))

# [1.2.0-dev.11](https://github.com/MorpheApp/morphe-patcher/compare/v1.2.0-dev.10...v1.2.0-dev.11) (2026-03-02)


### Bug Fixes

* additional use of streaming XML parser to speed up XML processing ([#66](https://github.com/MorpheApp/morphe-patcher/issues/66)) ([b2d45df](https://github.com/MorpheApp/morphe-patcher/commit/b2d45dffab5f903556e16179bdc63a381cd6d363))

# [1.2.0-dev.10](https://github.com/MorpheApp/morphe-patcher/compare/v1.2.0-dev.9...v1.2.0-dev.10) (2026-03-02)


### Bug Fixes

* Add missing XML namespaces when renaming package ([#65](https://github.com/MorpheApp/morphe-patcher/issues/65)) ([6568009](https://github.com/MorpheApp/morphe-patcher/commit/6568009327c2d106ca8c194adbd473623abc03e4))

# [1.2.0-dev.9](https://github.com/MorpheApp/morphe-patcher/compare/v1.2.0-dev.8...v1.2.0-dev.9) (2026-02-28)


### Bug Fixes

* Add some extension methods from morphe-library ([c2b667b](https://github.com/MorpheApp/morphe-patcher/commit/c2b667b2eb249598260f721924e2bbd7ed20dca4))

# [1.2.0-dev.8](https://github.com/MorpheApp/morphe-patcher/compare/v1.2.0-dev.7...v1.2.0-dev.8) (2026-02-28)


### Features

* Decouple morphe-library and morphe-patcher ([#60](https://github.com/MorpheApp/morphe-patcher/issues/60)) ([1785631](https://github.com/MorpheApp/morphe-patcher/commit/1785631d74e32475e6128713ab9c51d3a33645e1))

# [1.2.0-dev.7](https://github.com/MorpheApp/morphe-patcher/compare/v1.2.0-dev.6...v1.2.0-dev.7) (2026-02-28)


### Bug Fixes

* Specify UTF-8 during XML processing to prevent encoding issues on Windows ([#58](https://github.com/MorpheApp/morphe-patcher/issues/58)) ([842604d](https://github.com/MorpheApp/morphe-patcher/commit/842604d0504c904ac2abd6f2b268c164388384fc))

# [1.2.0-dev.6](https://github.com/MorpheApp/morphe-patcher/compare/v1.2.0-dev.5...v1.2.0-dev.6) (2026-02-26)


### Bug Fixes

* Use streaming XML processing ([#56](https://github.com/MorpheApp/morphe-patcher/issues/56)) ([e9d56d0](https://github.com/MorpheApp/morphe-patcher/commit/e9d56d0a1f06e04a72ff031974c894bd113985f5))

# [1.2.0-dev.5](https://github.com/MorpheApp/morphe-patcher/compare/v1.2.0-dev.4...v1.2.0-dev.5) (2026-02-25)


### Bug Fixes

* Sanitize strings.xml after decoding resources ([#55](https://github.com/MorpheApp/morphe-patcher/issues/55)) ([4155343](https://github.com/MorpheApp/morphe-patcher/commit/41553432f784dda34ddc3c2364279c6d2d87c3df))

# [1.2.0-dev.4](https://github.com/MorpheApp/morphe-patcher/compare/v1.2.0-dev.3...v1.2.0-dev.4) (2026-02-24)


### Bug Fixes

* Sanitize invalid XML characters in unpatched apk ([#54](https://github.com/MorpheApp/morphe-patcher/issues/54)) ([86a4087](https://github.com/MorpheApp/morphe-patcher/commit/86a4087c6345b5150ffa43e3695eba9e96224070))

# [1.2.0-dev.3](https://github.com/MorpheApp/morphe-patcher/compare/v1.2.0-dev.2...v1.2.0-dev.3) (2026-02-21)


### Features

* Add a version code field to `PackageMetadata` ([#47](https://github.com/MorpheApp/morphe-patcher/issues/47)) ([c8800fd](https://github.com/MorpheApp/morphe-patcher/commit/c8800fd725a32009542e45743dc533b73f97d747))

# [1.2.0-dev.2](https://github.com/MorpheApp/morphe-patcher/compare/v1.2.0-dev.1...v1.2.0-dev.2) (2026-02-21)


### Features

* Use arsclib during resource encoding/decoding and fix memory leaks ([#48](https://github.com/MorpheApp/morphe-patcher/issues/48)) ([7d0f837](https://github.com/MorpheApp/morphe-patcher/commit/7d0f837c939df44b78d81fff6c4f100c6afada49))

# [1.2.0-dev.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.1.1...v1.2.0-dev.1) (2026-02-09)


### Features

* Add public packageMetadata to `ResourcePatchContext` ([b2e7df8](https://github.com/MorpheApp/morphe-patcher/commit/b2e7df87c5b40a73c654e9d618d84d6d6ede77ac))

## [1.1.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.1.0...v1.1.1) (2026-02-04)


### Bug Fixes

* Resolve 'this' class type can match using 'contains' semantics ([#43](https://github.com/MorpheApp/morphe-patcher/issues/43)) ([8aff750](https://github.com/MorpheApp/morphe-patcher/commit/8aff7503fbee7752c48064fca5bd55030177143e))

## [1.1.1-dev.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.1.0...v1.1.1-dev.1) (2026-02-04)


### Bug Fixes

* Resolve 'this' class type can match using 'contains' semantics ([#43](https://github.com/MorpheApp/morphe-patcher/issues/43)) ([8aff750](https://github.com/MorpheApp/morphe-patcher/commit/8aff7503fbee7752c48064fca5bd55030177143e))

# [1.1.0](https://github.com/MorpheApp/morphe-patcher/compare/v1.0.1...v1.1.0) (2026-02-02)


### Features

* Extend `methodCall` defining class/name to `Fingerprint`, add additional defining class comparison methods ([#38](https://github.com/MorpheApp/morphe-patcher/issues/38)) ([2a7b618](https://github.com/MorpheApp/morphe-patcher/commit/2a7b6185fb47a2f2d5ec1bfda2d03b8a17f75de7))


### Performance Improvements

* Add methods to find all classes by String ([dcb13f3](https://github.com/MorpheApp/morphe-patcher/commit/dcb13f37a02b23735cc8fa0116aea7a0ace61954))

# [1.1.0-dev.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.0.2-dev.1...v1.1.0-dev.1) (2026-02-01)


### Features

* Extend `methodCall` defining class/name to `Fingerprint`, add additional defining class comparison methods ([#38](https://github.com/MorpheApp/morphe-patcher/issues/38)) ([2a7b618](https://github.com/MorpheApp/morphe-patcher/commit/2a7b6185fb47a2f2d5ec1bfda2d03b8a17f75de7))

## [1.0.2-dev.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.0.1...v1.0.2-dev.1) (2026-01-22)


### Performance Improvements

* Add methods to find all classes by String ([dcb13f3](https://github.com/MorpheApp/morphe-patcher/commit/dcb13f37a02b23735cc8fa0116aea7a0ace61954))

## [1.0.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.0.0...v1.0.1) (2026-01-10)


### Bug Fixes

* Allow `matchAfterWithin()` to be used on the first filter ([2fe26bc](https://github.com/MorpheApp/morphe-patcher/commit/2fe26bcea7a2b63cc9240c234744b2fd4cb5288a))

## [1.0.1-dev.1](https://github.com/MorpheApp/morphe-patcher/compare/v1.0.0...v1.0.1-dev.1) (2026-01-08)


### Bug Fixes

* Allow `matchAfterWithin()` to be used on the first filter ([2fe26bc](https://github.com/MorpheApp/morphe-patcher/commit/2fe26bcea7a2b63cc9240c234744b2fd4cb5288a))

# 1.0.0 (2025-12-11)


### Features

* Add methodCall and fieldAccess filters from Reference objects ([d7f6ac2](https://github.com/MorpheApp/morphe-patcher/commit/d7f6ac2039f9c11c10b49231448a80c3032478ed))
* First release ([5fc0c46](https://github.com/MorpheApp/morphe-patcher/commit/5fc0c46599fc9e3365be574aef0cc7512285fb62))
