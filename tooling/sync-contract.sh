#!/usr/bin/env bash
# Vendor the declarations of the server's TypeScript API types that the contract models mirror, and
# the list of routes a guest may call.
#
# The only writer of contract/src/test/resources/api-contract/{types.ts,guest-routes.txt,SYNCED_AT};
# never edit those by hand. interfaces.txt (same directory) names the interfaces and type aliases to keep, one per
# line; ContractTest holds that list equal to the set of models it checks. Comments are dropped, so
# the snapshot is the field and literal names alone. SYNCED_AT records the commit it was taken from.
#
# Needs read access to the backend repository: a logged-in gh, or GH_TOKEN in the environment.
#
# Usage: tooling/sync-contract.sh <backend-sha>

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "usage: $0 <backend-sha>" >&2
    exit 1
fi

sha="$1"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$root/contract/src/test/resources/api-contract"
names="$dest/interfaces.txt"
repo="frederikberg/pai-cloud"
path="web/src/api/types.ts"
auth_path="backend/src/pai_cloud/auth.py"

source_file="$(mktemp)"
trap 'rm -f "$source_file"' EXIT

echo "fetching $path @ $sha"
gh api "repos/$repo/contents/$path?ref=$sha" -H "Accept: application/vnd.github.raw" > "$source_file"

# Keeps each wanted declaration whole: an interface runs to the closing brace in column 0, a type
# alias to the line that ends in a semicolon. Whole-line comments and trailing ones go.
awk -v names_file="$names" '
    BEGIN {
        while ((getline line < names_file) > 0) if (line != "") wanted[line] = 1
        close(names_file)
    }
    function clean(text) {
        sub(/[ \t]*\/\/.*$/, "", text)
        gsub(/\/\*[^*]*\*\//, "", text)
        return text
    }
    skipping_comment {
        if ($0 ~ /\*\//) skipping_comment = 0
        next
    }
    /^[ \t]*\/\*/ {
        if ($0 !~ /\*\//) skipping_comment = 1
        next
    }
    /^[ \t]*(\/\/|\*)/ { next }
    !in_decl && /^export (interface|type) / {
        name = $3
        sub(/[^A-Za-z0-9_].*$/, "", name)
        if (!(name in wanted)) next
        in_decl = 1
        kind = $2
        seen[name] = 1
    }
    in_decl {
        line = clean($0)
        if (line ~ /^[ \t]*$/) next
        print line
        if ((kind == "interface" && line ~ /^}/) || (kind == "type" && line ~ /;[ \t]*$/)) {
            in_decl = 0
            print ""
        }
        next
    }
    END {
        for (n in wanted) if (!(n in seen)) { print "missing declaration: " n > "/dev/stderr"; failed = 1 }
        if (failed) exit 1
    }
' "$source_file" > "$dest/types.ts.new"

# The guest route table: `("METHOD", "/path"): gate,` entries of GUEST_REACHABLE, one `METHOD /path` per line,
# kept to the route families the app uses.
echo "fetching $auth_path @ $sha"
gh api "repos/$repo/contents/$auth_path?ref=$sha" -H "Accept: application/vnd.github.raw" > "$source_file"
awk '
    /^GUEST_REACHABLE: / { in_table = 1; next }
    in_table && /^}/ { in_table = 0 }
    in_table && match($0, /\("[A-Z]+", "[^"]+"\)/) {
        entry = substr($0, RSTART + 1, RLENGTH - 2)
        gsub(/"/, "", entry)
        sub(/, /, " ", entry)
        if (entry ~ / \/api\/(me|sessions?|session-types|session-models|skills|usage|settings\/me|notifications|push|drafts|messages|sign-ins|favorites|browse)(\/|$)/) print entry
    }
' "$source_file" | sort > "$dest/guest-routes.txt.new"
if [ ! -s "$dest/guest-routes.txt.new" ]; then
    echo "no guest routes found in $auth_path" >&2
    exit 1
fi

mv "$dest/types.ts.new" "$dest/types.ts"
mv "$dest/guest-routes.txt.new" "$dest/guest-routes.txt"
echo "$sha" > "$dest/SYNCED_AT"
echo "vendored $dest against $sha"
