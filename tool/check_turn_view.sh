#!/usr/bin/env bash
# Runs the checks of the bird's-eye view rules (ios/Classes/TurnView.swift).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
swiftc -parse-as-library -o "$out/check" ios/Classes/TurnView.swift tool/turn_view_check.swift
"$out/check"
