# Vendored upstream conformance schemas

Two `conformance_schema.json` files from [a2ui-project/a2ui](https://github.com/a2ui-project/a2ui),
pinned by commit SHA, so `scripts/check-a2ui-conformance.py` runs offline and in CI.

| file | upstream SHA | `Catalog` constraint |
|---|---|---|
| `conformance_schema-legacy-main-44a420b.json` | `44a420b67957fafc0b02d55a153fdaf72e32ffb5` | none — no `properties`, `additionalProperties` unset |
| `conformance_schema-v1-candidate-bb4600b.json` | `bb4600bcc11bb4ce501e701fe60bbe271d70a734` | `additionalProperties: false`, property is **`catalogSchema`** |

That difference is the whole story of the fix: `automotive.yaml` used `catalog_schema`, which the
legacy schema accepted because it constrained nothing, and which the v1 candidate rejects with
*"Additional properties are not allowed ('catalog_schema' was unexpected)"*.

**What a green run here does and does not mean.** Upstream's harness at both SHAs is
`conformance/tests/test_conformance_yaml.py`, which is 53 lines and does exactly one thing per file:

```python
jsonschema.validate(instance=yaml_data, schema=SCHEMA)
```

It is a **schema validator, not a behavioural runner**. It does not execute `prune`, and the schema
places no enum on `action`, so any action string validates. Passing therefore means *this file is
shaped the way upstream expects*, and nothing at all about whether the pruning it describes is
correct. The behavioural claims are carried by this repository's own tests
(`A2uiCatalogContractTest` pins the derived allowed-sets against every case in this file).

Refresh by re-fetching at a newer SHA and updating the table; do not hand-edit.
