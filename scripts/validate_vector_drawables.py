#!/usr/bin/env python3
"""Validate Switchly vector drawable structure without external dependencies."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "http://schemas.android.com/apk/res/android"
A = f"{{{ANDROID}}}"
DRAWABLE_DIR = Path(__file__).resolve().parents[1] / "app/src/main/res/drawable"

ALLOWED_SIZE_EXCEPTIONS = {
    "language_24.xml": ("24dp", "24dp", "24", "24"),
    "widget_active_timer_preview.xml": ("200dp", "100dp", "240", "120"),
    "widget_notifications_20.xml": ("20dp", "20dp", "960", "960"),
    "widget_toggle_off_20.xml": ("20dp", "20dp", "960", "960"),
}


def fail(message: str, failures: list[str]) -> None:
    failures.append(message)


def main() -> int:
    failures: list[str] = []
    files = sorted(DRAWABLE_DIR.glob("*.xml"))

    for path in files:
        raw = path.read_text(encoding="utf-8")
        if raw.lstrip().startswith("<?xml"):
            fail(f"{path.name}: drawable XML declarations are not used", failures)

        try:
            root = ET.fromstring(raw)
        except ET.ParseError as exc:
            fail(f"{path.name}: invalid XML: {exc}", failures)
            continue

        if root.tag.split("}")[-1] != "vector":
            fail(f"{path.name}: expected a vector root", failures)
            continue

        dimensions = (
            root.get(A + "width"),
            root.get(A + "height"),
            root.get(A + "viewportWidth"),
            root.get(A + "viewportHeight"),
        )
        expected = ALLOWED_SIZE_EXCEPTIONS.get(
            path.name,
            ("24dp", "24dp", "960", "960"),
        )
        if dimensions != expected:
            fail(
                f"{path.name}: expected dimensions {expected}, found {dimensions}",
                failures,
            )

        root_attrs = list(root.attrib)
        expected_root_order = [
            A + "width",
            A + "height",
            A + "viewportWidth",
            A + "viewportHeight",
        ]
        if root_attrs[:4] != expected_root_order:
            fail(f"{path.name}: vector attributes are not in the standard order", failures)

        for index, child in enumerate(root, start=1):
            if child.tag.split("}")[-1] != "path":
                continue
            attrs = list(child.attrib)
            if not attrs or attrs[0] != A + "pathData":
                fail(f"{path.name}: path {index} must start with pathData", failures)
            if A + "fillColor" in attrs and attrs.index(A + "fillColor") < attrs.index(A + "pathData"):
                fail(f"{path.name}: path {index} fillColor must follow pathData", failures)

    if failures:
        print("Vector drawable validation failed:")
        for message in failures:
            print(f"- {message}")
        return 1

    print(f"Validated {len(files)} vector drawables.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
