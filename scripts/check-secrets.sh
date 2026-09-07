#!/usr/bin/env sh
# Refuses to let a credential reach a tracked file.
#
# This exists because it has already happened twice: real OpenRouter and ElevenLabs keys were
# typed into `.env.example` instead of `.env`. The filenames differ by eight characters, one is
# tracked and one is ignored, and editors autocomplete to the wrong one. Neither incident reached
# a commit, but only because somebody looked.
#
# Two rules, deliberately narrow so it can be trusted rather than routinely overridden:
#
#   1. Credential-shaped variables in tracked *.example files must have empty values. That is what
#      a template is: names without values.
#   2. No tracked file outside test sources may contain something shaped like a real key.
#
# Runs in CI and as a pre-commit hook. POSIX sh so it works from Git Bash on Windows as well as
# from a Linux runner.

set -eu

fail=0
note() { printf '%s\n' "$*" >&2; }

# --- Rule 1: template files carry names, not values ----------------------------------------
for template in .env.example local.properties.example; do
    [ -f "$template" ] || continue
    # A credential-shaped name with anything after the '=' . DATABASE_URL and similar are not
    # matched: the local development default there is not a secret, and pretending otherwise
    # would teach people to ignore this check.
    # awk rather than grep so the *value* can be judged, not just its presence. A placeholder
    # like MWDAT_CLIENT_TOKEN=0 is legitimate config and must not be flagged: a check that cries
    # wolf on a valid file gets switched off within a week. Real credentials are long and are
    # never purely numeric, so the bar is 8+ characters containing at least one non-digit.
    hits=$(awk -F= '
        /^[A-Za-z0-9_]*(API_KEY|TOKEN|SECRET|PASSWORD)[A-Za-z0-9_]*=/ {
            name = $1
            value = substr($0, index($0, "=") + 1)
            gsub(/^[ \t]+|[ \t\r]+$/, "", value)
            if (length(value) >= 8 && value ~ /[^0-9]/) print NR ":" name
        }
    ' "$template" || true)
    if [ -n "$hits" ]; then
        note "SECRET CHECK FAILED: $template has values where it should have empty placeholders."
        note ""
        note "$(printf '%s\n' "$hits" | cut -d= -f1)"
        note ""
        note "  $template is tracked by git. The file you meant to edit is the one without"
        note "  '.example', which is git-ignored."
        note ""
        note "  To fix, keeping the keys:"
        note "    cp $template ${template%.example}    # only if you have not already filled that in"
        note "    git checkout -- $template"
        fail=1
    fi
done

# --- Rule 2: nothing key-shaped anywhere tracked -------------------------------------------
# Test fixtures deliberately contain fake keys to prove redaction works, so test sources are
# excluded rather than the patterns being loosened.
patterns='sk-or-v1-[A-Za-z0-9]{20,}|sk-[A-Za-z0-9]{32,}|ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|xi-[A-Za-z0-9]{32,}'
for file in $(git ls-files); do
    case "$file" in
        */src/test/*|scripts/check-secrets.sh) continue ;;
    esac
    [ -f "$file" ] || continue
    if grep -qE "$patterns" "$file" 2>/dev/null; then
        note "SECRET CHECK FAILED: $file looks like it contains a real credential."
        note "  Remove it, and rotate the key: anything committed should be treated as public."
        fail=1
    fi
done

if [ "$fail" -ne 0 ]; then
    note ""
    note "Nothing has been committed. Fix the above and try again."
    exit 1
fi

printf 'Secret check passed: no credentials in tracked files.\n'
