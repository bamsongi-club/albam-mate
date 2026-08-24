#!/bin/sh

set -eu

if ! command -v git >/dev/null 2>&1; then
    printf 'Git command not found. Install Git first.\n' >&2
    exit 1
fi

script_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)

if ! repo_root=$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null); then
    printf 'Git repository not found. Initialize or clone the repository first.\n' >&2
    exit 1
fi

hook_path="$repo_root/.githooks/pre-commit"
gradle_wrapper_path="$repo_root/gradlew"

if [ ! -f "$hook_path" ]; then
    printf 'Pre-commit hook not found: %s\n' "$hook_path" >&2
    exit 1
fi

if [ ! -f "$gradle_wrapper_path" ]; then
    printf 'Gradle Wrapper not found: %s\n' "$gradle_wrapper_path" >&2
    exit 1
fi

chmod +x "$hook_path" "$gradle_wrapper_path"
git -C "$repo_root" config --local core.hooksPath .githooks

configured_hooks_path=$(git -C "$repo_root" config --local --get core.hooksPath)
if [ "$configured_hooks_path" != '.githooks' ]; then
    printf 'Unexpected core.hooksPath: %s\n' "$configured_hooks_path" >&2
    exit 1
fi

printf 'Git hooks enabled: core.hooksPath=.githooks\n'
