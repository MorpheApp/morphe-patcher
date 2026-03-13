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
