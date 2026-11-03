#!/bin/sh
# Run with sh config/tests/docker-entrypoint-test.sh [artifact-parent-directory].
# Artifacts are retained for inspection; this test does not delete directories.
set -eu

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
entrypoint=$script_directory/../docker-entrypoint.sh
artifact_parent=${1:-${TMPDIR:-/tmp}}
mkdir -p "$artifact_parent"
test_directory=$(mktemp -d "$artifact_parent/dzp-fcs-entrypoint.XXXXXX")
checks=0

fail() {
    printf '%s\n' "FAIL: $* (artifacts: $test_directory)" >&2
    exit 1
}

new_case() {
    checks=$((checks + 1))
    case_home=$test_directory/case-$checks
    mkdir -p "$case_home/webapps" "$case_home/dzp-fcs"
    printf '%s\n' 'test application WAR' > "$case_home/dzp-fcs/dzp-fcs.war"
}

invoke() {
    env CATALINA_HOME="$case_home" \
        DZP_FCS_CONTEXT_PATH="$1" DZP_FCS_PUBLIC_PATH="$2" \
        sh "$entrypoint" sh -c \
        'printf "%s\n%s\n" "$DZP_FCS_CONTEXT_PATH" "$DZP_FCS_PUBLIC_PATH"' \
        > "$case_home/stdout" 2> "$case_home/stderr"
}

expect_path() {
    input=$1
    expected=$2
    filename=$3
    public_input=$4
    public_expected=$5
    new_case
    invoke "$input" "$public_input" || fail "accepted path $input"
    actual=$(cat "$case_home/stdout")
    wanted=$(printf '%s\n%s\n' "$expected" "$public_expected")
    [ "$actual" = "$wanted" ] || fail "normalized path/environment for $input"
    cmp -s "$case_home/dzp-fcs/dzp-fcs.war" "$case_home/webapps/$filename.war" \
        || fail "Tomcat WAR name for $input"
    # A normal Tomcat restart may retain both its WAR and unpacked directory.
    mkdir "$case_home/webapps/$filename"
    invoke "$input" "$public_input" || fail "restart for $input"
}

expect_rejected() {
    new_case
    if invoke "$1" "$2"; then
        fail "unexpectedly accepted context=$1 public=$2"
    fi
    [ -s "$case_home/stderr" ] || fail "missing diagnostic for $1"
    for artifact in "$case_home/webapps"/*; do
        [ ! -e "$artifact" ] || fail "invalid path created a deployment: $1"
    done
}

expect_path '' / ROOT '' /
expect_path / / ROOT '' /
expect_path /app/dzp-fcs /app/dzp-fcs 'app#dzp-fcs' '' /app/dzp-fcs
expect_path /app/dzp-fcs/ /app/dzp-fcs 'app#dzp-fcs' '' /app/dzp-fcs
expect_path /a_b.c-1~ /a_b.c-1~ 'a_b.c-1~' '' /a_b.c-1~
expect_path / / ROOT /app/dzp-fcs/ /app/dzp-fcs
expect_path /internal /internal internal /public /public
expect_path /ROOT/x /ROOT/x 'ROOT#x' '' /ROOT/x
expect_path / / ROOT /ROOT /ROOT

for invalid in app // /app//part /app// /./ /../ /app/. /app/.. \
    /app/./part /app/../part '/app?x=1' '/app#part' '/app%2fpart' \
    '/app space' '/app\part' '/app:part' '/app;part' '/app*' '/app[part]' '/app/ä'; do
    expect_rejected "$invalid" ''
done
expect_rejected / 'relative-public'
expect_rejected / '/public/../path'
expect_rejected /ROOT ''
expect_rejected /ROOT/ ''

new_case
invoke / '' || fail 'initial root deployment'
if invoke /app/dzp-fcs ''; then
    fail 'context switch silently left a second application'
fi
[ ! -e "$case_home/webapps/app#dzp-fcs.war" ] || fail 'context switch wrote another WAR'
cmp -s "$case_home/dzp-fcs/dzp-fcs.war" "$case_home/webapps/ROOT.war" \
    || fail 'context switch altered existing WAR'

new_case
printf '%s\n' 'different application' > "$case_home/webapps/ROOT.war"
if invoke / ''; then fail 'overwrote an existing different WAR'; fi
[ "$(cat "$case_home/webapps/ROOT.war")" = 'different application' ] \
    || fail 'modified an existing different WAR'

new_case
printf '%s\n' 'unrelated' > "$case_home/webapps/.hidden"
if invoke / ''; then fail 'ignored hidden deployment content'; fi

new_case
mkdir "$case_home/webapps/ROOT"
if invoke / ''; then fail 'accepted orphaned unpacked application'; fi

new_case
mkdir "$case_home/webapps/ROOT.war"
if invoke / ''; then fail 'accepted a directory in place of the WAR'; fi

printf '%s\n' "Passed $checks entrypoint scenarios, including restart checks. Artifacts: $test_directory"
