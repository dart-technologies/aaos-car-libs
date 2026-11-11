# A2UI automotive conformance suite

Driver-distraction cases for [A2UI](https://github.com/a2ui-project/a2ui) in A2UI's own conformance
format: each AAOS UX-restriction level is one `action: prune` case over the v1.0 basic catalog, the
same mechanism `conformance/core/accessibility.yaml` uses. Staged here; not proposed upstream (draft:
[`docs/upstream/A2UI-PR-DRAFT.md`](../docs/upstream/A2UI-PR-DRAFT.md)).

| Path | What |
|---|---|
| `conformance/automotive.yaml` | The 12 cases below (A2UI v1.0) |
| `upstream-schemas/` | Two pinned upstream conformance schemas |
| `agent-gateway-v1.schema.json` | Provider-neutral `a2ui.gateway.v1` envelopes; see [PROVIDERS](../docs/PROVIDERS.md) |
| `demo/` | NYC scenarios, dated context, agent profiles, gitignored audio cache; see [DEMO](../docs/DEMO.md) |
| `prompts/` | Corpus-generation prompt for `scripts/claude-corpus-tester.py` |

## Cases

| Case | Restriction | Prunes to |
|---|---|---|
| `baseline_allows_full_catalog` | `BASELINE` 0x0 | all 18 |
| `no_video_drops_video_only` | `NO_VIDEO` 0x10 | 17 (AudioPlayer survives) |
| `no_keyboard_drops_text_entry` | `NO_KEYBOARD` 0x8 | 16 |
| `no_filtering_drops_search_affordances` | `NO_FILTERING` 0x2 | 16 |
| `no_setup_drops_configuration_components` | `NO_SETUP` 0x40 | 13 |
| `limit_content_drops_deep_navigation` | `LIMIT_CONTENT` 0x20 | 15 (component half only) |
| `fully_restricted_prunes_to_glanceable_set` | `FULLY_RESTRICTED` 0x1ff | 6 |
| `driving_state_unknown_fails_closed` | `DRIVING_STATE_UNKNOWN` | 6, as fully restricted |
| `passenger_display_unrestricted_while_moving` | per-display `BASELINE` | all 18 |
| `surface_repruned_for_destination_display` | the destination display wins | 6 |
| `rear_display_allows_video_with_audio` | rear zone | all 18 |
| `out_of_catalog_component_is_not_reintroduced` | enforcement boundary | 6 |

Catalog: `AudioPlayer Button Card CheckBox ChoicePicker Column DateTimeInput Divider Icon Image List
Modal Row Slider Tabs Text TextField Video` (`specification/v1_0/catalogs/basic/catalog.json`).

## Restriction constants

Read from `android.car.jar` with `javap -constants` (a widely copied table is wrong in most rows):

```
BASELINE 0x0   NO_DIALPAD 0x1   NO_FILTERING 0x2   LIMIT_STRING_LENGTH 0x4   NO_KEYBOARD 0x8
NO_VIDEO 0x10  LIMIT_CONTENT 0x20  NO_SETUP 0x40  NO_TEXT_MESSAGE 0x80  NO_VOICE_TRANSCRIPTION 0x100
FULLY_RESTRICTED 0x1ff   (the union of the nine, not a bit of its own)
```

Test the composite as `(active & 0x1ff) == 0x1ff`, never `!= 0` or `==` on the whole field.

## What `prune` cannot express

- **Values**: `LIMIT_CONTENT` item count and depth, `LIMIT_STRING_LENGTH` truncation, glance budgets.
  This repo enforces them outside A2UI (`A2uiValueLimits`).
- **Provenance**: a renderer pruning on a never-delivered reading emits a perfectly conformant,
  permissive catalog. This repo carries `RestrictionsSource` outside A2UI.
- `NO_DIALPAD`: the basic catalog has no dialpad component.

Both gaps are one spec proposal, sequenced after the catalog PR:
[`docs/upstream/A2UI-SPEC-PROPOSAL-DRAFT.md`](../docs/upstream/A2UI-SPEC-PROPOSAL-DRAFT.md).

## Checking

`scripts/check-a2ui-conformance.py` (CI) validates this file against both pinned schemas, legacy
`main` `44a420b` and v1 at upstream HEAD `294dacce`, and validates the vendored v1 basic catalog
against the vendored v1 catalog definition. That is a shape check only: upstream's harness never runs
`prune`. Behaviour is pinned by `A2uiCatalogContractTest`, which derives every case's allowed set from
`A2uiRestrictionPolicy`. To run upstream's own validator (it globs only `core/`, `agent/`, `extensions/`):

```bash
git clone https://github.com/a2ui-project/a2ui.git
cp conformance/automotive.yaml a2ui/conformance/core/automotive.yaml
cd a2ui && python3 -m pytest conformance/tests/test_conformance_yaml.py -q
```
