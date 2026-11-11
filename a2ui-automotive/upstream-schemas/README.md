# Vendored upstream schemas

Pinned files from [a2ui-project/a2ui](https://github.com/a2ui-project/a2ui), by commit SHA, so
`scripts/check-a2ui-conformance.py` runs offline and in CI.

| file | upstream path | upstream SHA |
|---|---|---|
| `conformance_schema-legacy-main-44a420b.json` | `specification/JSON/conformance/conformance_schema.json` | `44a420b67957fafc0b02d55a153fdaf72e32ffb5` |
| `conformance_schema-v1-294dacce.json` | `conformance/conformance_schema.json` | `294dacce0a74545c35271e39fad9e55d1069f1c4` |
| `basic-catalog-v1-294dacce.json` | `catalogs/basic/v1/catalog.json` | `294dacce0a74545c35271e39fad9e55d1069f1c4` |
| `catalog-definition-v1-294dacce.json` | `specification/v1_0/json/catalog_definition.json` | `294dacce0a74545c35271e39fad9e55d1069f1c4` |

The legacy schema constrains nothing (no `properties`, `additionalProperties` unset); the v1
schema sets `additionalProperties: false` with the property named **`catalogSchema`**. That
difference is the whole story of the original fix: `automotive.yaml` used `catalog_schema`, which
the legacy schema accepted and v1 rejects with *"Additional properties are not allowed
('catalog_schema' was unexpected)"*.

The v1 pin supersedes the earlier v1-candidate pin (`bb4600bcc11bb4ce501e701fe60bbe271d70a734`,
deleted): at the time of pinning, upstream has no `v1.0` tag (latest tags are `v0.10.0+swift`,
`v0.9`, `v0.8`), so `main` at `294dacce` is the newest v1 shape available. Its basic catalog
carries the same 18 components the `bb4600b` pin did — no drift. The schema file itself moved
from `specification/JSON/conformance/` to `conformance/` between the two pins.

**What a green run here does and does not mean.** Upstream's harness
(`conformance/tests/test_conformance_yaml.py`, 53 lines at the old pins, ~990 at `294dacce`)
schema-validates each file and additionally checks node fixtures and renderer-error payloads when a
case carries them. Ours carries neither, so the check is the same shape check. It is a **schema
validator, not a behavioural runner**. It does not execute `prune`, and the schema places no enum
on `action`, so any action string validates. Passing therefore means *this file is shaped the way
upstream expects*, and nothing at all about whether the pruning it describes is correct. The
behavioural claims are carried by this repository's own tests (`A2uiCatalogContractTest` pins the
derived allowed-sets against every case in this file). Verified 2026-11-11: upstream's own harness
at `294dacce` accepts `automotive.yaml` (`1 passed, 137 deselected`).

Refresh by re-fetching at a newer SHA and updating the table; do not hand-edit.
