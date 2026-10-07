#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/../../../.."
test_binary=$(mktemp "${TMPDIR:-/tmp}/primecap-root-audio-test.XXXXXX")
trap 'rm -f "$test_binary"' EXIT
"${CC:-cc}" -std=gnu11 -Wall -Wextra -Werror -pthread \
    app/src/test/cpp/root_audio_test.c -o "$test_binary"
"$test_binary"
