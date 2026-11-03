#!/bin/sh
set -eu

fail() {
    printf '%s\n' "dzp-fcs: $*" >&2
    exit 1
}

normalize_path() {
    # Each call runs in command substitution, so this does not change the app's
    # locale. Make character ranges strictly ASCII in every container locale.
    LC_ALL=C
    export LC_ALL
    value=$1
    setting=$2
    case "$value" in
        /*) ;;
        *) fail "$setting must start with /" ;;
    esac
    case "$value" in
        *//*|*[!A-Za-z0-9._~/-]*)
            fail "$setting contains an invalid path segment or character" ;;
    esac
    if [ "$value" != / ]; then
        value=${value%/}
    fi
    case "$value/" in
        */./*|*/../*) fail "$setting must not contain . or .. segments" ;;
    esac
    printf '%s' "$value"
}

DZP_FCS_CONTEXT_PATH=$(normalize_path "${DZP_FCS_CONTEXT_PATH:-/}" DZP_FCS_CONTEXT_PATH)
DZP_FCS_PUBLIC_PATH=$(normalize_path "${DZP_FCS_PUBLIC_PATH:-$DZP_FCS_CONTEXT_PATH}" DZP_FCS_PUBLIC_PATH)
[ "$DZP_FCS_CONTEXT_PATH" != /ROOT ] \
    || fail "DZP_FCS_CONTEXT_PATH=/ROOT is reserved by Tomcat; use / for the root context"
export DZP_FCS_CONTEXT_PATH DZP_FCS_PUBLIC_PATH

: "${CATALINA_HOME:?CATALINA_HOME must identify the Tomcat installation}"
source_war=$CATALINA_HOME/dzp-fcs/dzp-fcs.war
webapps=$CATALINA_HOME/webapps
[ -f "$source_war" ] || fail "Application WAR is missing: $source_war"
[ -d "$webapps" ] && [ ! -L "$webapps" ] || fail "webapps must be a regular directory: $webapps"

if [ "$DZP_FCS_CONTEXT_PATH" = / ]; then
    application_name=ROOT
else
    # Tomcat represents nested context paths with # in the WAR filename.
    application_name=$(printf '%s' "${DZP_FCS_CONTEXT_PATH#/}" | tr / '#')
fi
deployed_war=$webapps/$application_name.war
expanded_app=$webapps/$application_name

# A restart may retain this WAR and its unpacked directory. Any other deployment
# indicates a reused webapps volume or a context change: fail without deleting it.
for deployment in "$webapps"/* "$webapps"/.[!.]* "$webapps"/..?*; do
    [ -e "$deployment" ] || [ -L "$deployment" ] || continue
    case "$deployment" in
        "$deployed_war")
            [ -f "$deployment" ] && [ ! -L "$deployment" ] \
                || fail "Expected a regular WAR file: $deployment"
            cmp -s "$source_war" "$deployment" \
                || fail "Existing WAR differs from this image: $deployment" ;;
        "$expanded_app")
            [ -d "$deployment" ] && [ ! -L "$deployment" ] \
                || fail "Expected a regular application directory: $deployment" ;;
        *) fail "Unexpected deployment in webapps: $deployment. Use a fresh container/webapps directory when changing DZP_FCS_CONTEXT_PATH." ;;
    esac
done
if [ -d "$expanded_app" ] && [ ! -f "$deployed_war" ]; then
    fail "An unpacked application exists without the matching WAR: $expanded_app"
fi
if [ ! -f "$deployed_war" ]; then
    cp "$source_war" "$deployed_war"
fi

if [ "$#" -eq 0 ]; then
    set -- catalina.sh run
fi
exec "$@"
