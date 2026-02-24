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
