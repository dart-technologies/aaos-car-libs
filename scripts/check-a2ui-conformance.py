#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0 — see scripts/check-build-coverage.py.
"""Validates automotive conformance plus the provider-neutral OEM gateway contract.

A pass means the file is shaped as upstream expects under both pinned schemas (legacy main and the
v1 candidate); upstream's harness only validates schemas. Whether the pruning is correct is
A2uiCatalogContractTest's job.

Exit codes: 0 valid against both, 1 invalid against either, 2 could not be determined (jsonschema
missing, schema or subject unreadable) — which is not a pass.
"""

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SUBJECT = ROOT / 'a2ui-automotive' / 'conformance' / 'automotive.yaml'
SCHEMA_DIR = ROOT / 'a2ui-automotive' / 'upstream-schemas'
SCHEMAS = [
    ('legacy main   44a420b', SCHEMA_DIR / 'conformance_schema-legacy-main-44a420b.json'),
    ('v1 candidate  bb4600b', SCHEMA_DIR / 'conformance_schema-v1-candidate-bb4600b.json'),
]
GATEWAY_SCHEMA = ROOT / 'a2ui-automotive' / 'agent-gateway-v1.schema.json'


def main() -> int:
    try:
        import yaml
        import jsonschema
    except ImportError as e:
        print(f'INDETERMINATE: {e.name} is not installed, so conformance was NOT checked. '
              'Install the pinned set from scripts/requirements-a2ui-conformance-py312-linux.txt',
              file=sys.stderr)
        return 2

    if not SUBJECT.is_file():
        print(f'INDETERMINATE: {SUBJECT.relative_to(ROOT)} is missing', file=sys.stderr)
        return 2

    try:
        data = yaml.safe_load(SUBJECT.read_text())
    except yaml.YAMLError as e:
        print(f'FAIL: {SUBJECT.name} is not valid YAML: {e}', file=sys.stderr)
        return 1

    if not isinstance(data, list) or not data:
        print(f'INDETERMINATE: {SUBJECT.name} parsed to {type(data).__name__}, expected a non-empty '
              'list of test cases — nothing was checked', file=sys.stderr)
        return 2

    failed = False
    for label, path in SCHEMAS:
        if not path.is_file():
            print(f'INDETERMINATE: vendored schema missing: {path.name}', file=sys.stderr)
            return 2
        schema = json.loads(path.read_text())
        try:
            jsonschema.validate(instance=data, schema=schema)
            print(f'  OK   {label}: {len(data)} case(s) validate')
        except jsonschema.ValidationError as e:
            failed = True
            where = '/'.join(str(p) for p in e.absolute_path) or '<root>'
            print(f'  FAIL {label}: {e.message}', file=sys.stderr)
            print(f'       at: {where}', file=sys.stderr)

    if not GATEWAY_SCHEMA.is_file():
        print(f'INDETERMINATE: gateway schema missing: {GATEWAY_SCHEMA.name}', file=sys.stderr)
        return 2
    try:
        gateway_schema = json.loads(GATEWAY_SCHEMA.read_text())
        jsonschema.Draft202012Validator.check_schema(gateway_schema)
    except (json.JSONDecodeError, jsonschema.SchemaError) as e:
        print(f'FAIL: {GATEWAY_SCHEMA.name} is not a valid Draft 2020-12 schema: {e}',
              file=sys.stderr)
        return 1

    gateway_samples = [
        {
            'schema': 'a2ui.gateway.v1', 'type': 'turn', 'provider': 'claude',
            'executionProfile': 'quality_cached',
            'sessionId': 'session', 'turnId': 'turn', 'inputText': 'Show albums',
            'capabilities': {'versions': ['v0.9'], 'catalogs': ['automotive']},
            'display': {'id': 0, 'activeRestrictions': 0, 'trustworthy': True},
        },
        {
            'schema': 'a2ui.gateway.v1', 'type': 'actionResult', 'provider': 'chatgpt',
            'sessionId': 'session', 'turnId': 'turn', 'surfaceId': 'surface',
            'componentId': 'button', 'actionId': 'media.play', 'outcome': 'ALLOWED',
        },
        {
            'schema': 'a2ui.gateway.v1', 'provider': 'gemini', 'sessionId': 'session',
            'turnId': 'turn', 'source': 'fallback', 'messages': [{
                'version': 'v0.9',
                'createSurface': {'surfaceId': 'surface', 'catalogId': 'automotive'},
            }],
        },
        {
            'schema': 'a2ui.gateway.v1', 'ack': 'actionResult', 'provider': 'grok',
            'sessionId': 'session', 'turnId': 'turn',
        },
        {
            'schema': 'a2ui.gateway.v1', 'type': 'turn', 'provider': 'muse',
            'executionProfile': 'realtime_low_latency',
            'sessionId': 'session', 'turnId': 'turn', 'inputText': 'Find coffee',
            'capabilities': {'versions': ['v0.9'], 'catalogs': ['automotive']},
            'display': {'id': 0, 'activeRestrictions': 511, 'trustworthy': False},
        },
        {
            'schema': 'a2ui.gateway.v1', 'provider': 'muse', 'turnId': 'turn',
            'error': {'code': 'invalid_output', 'detail': 'generation_schema'},
        },
    ]
    # Each must be rejected: an unknown provider or source, and an error detail with free text.
    gateway_rejects = [
        dict(gateway_samples[2], provider='llama'),
        dict(gateway_samples[2], source='guessed'),
        dict(gateway_samples[-1], error={'code': 'upstream', 'detail': 'vendor said: hello'}),
    ]
    for sample in gateway_samples:
        try:
            jsonschema.validate(instance=sample, schema=gateway_schema)
        except jsonschema.ValidationError as e:
            failed = True
            print(f'  FAIL gateway {sample.get("type", sample.get("ack", "response"))}: '
                  f'{e.message}', file=sys.stderr)
    for sample in gateway_rejects:
        if jsonschema.Draft202012Validator(gateway_schema).is_valid(sample):
            failed = True
            print(f'  FAIL gateway accepted an invalid envelope: {sorted(sample)}',
                  file=sys.stderr)
    if not failed:
        print(f'  OK   gateway v1: {len(gateway_samples)} envelope shape(s) validate, '
              f'{len(gateway_rejects)} invalid one(s) rejected')

    if failed:
        print('\nThe schemas are vendored and pinned in a2ui-automotive/upstream-schemas/. If '
              'upstream changed shape, refresh them at a newer SHA and update that README — do not '
              'edit the vendored copies by hand.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
