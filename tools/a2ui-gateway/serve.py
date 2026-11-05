#!/usr/bin/env python3
# Copyright (C) 2026 Dart Technologies
# Licensed under the Apache License, Version 2.0.
"""Run the dev gateway. See docs/PROVIDERS.md."""

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from a2ui_gateway.server import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
