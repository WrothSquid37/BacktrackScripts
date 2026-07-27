#!/bin/bash
set -euo pipefail

TIMESTAMP_FORMAT="%m%d%Y%H%M%S"
BACKUP_TYPES=(full fullzip zip rsync)

fail() {
    echo "$1" >&2
    exit "${2:-1}"
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "$1 is not installed or not available in PATH"
}

usage() {
    cat <<'EOF'
Usage: performbackup.sh <source> <destination> <type>

Create a backup of a local directory either locally or on a remote machine.

Arguments:
  source       Directory to back up
  destination  Local backup directory, or remote destination as user@host:/path
  type         Backup mode:
                 full     Copy the folder into destination/<timestamp>/
                 fullzip  full copy plus a timestamped zip in destination/
                 zip      Create a full snapshot as destination/<timestamp>.zip
                 rsync    Incremental sync into destination/ (fixed folder, no timestamp)

Examples:
  performbackup.sh /var/www/data /backups full
  performbackup.sh /var/www/data /backups zip
  performbackup.sh /var/www/data /backups rsync
  performbackup.sh /var/www/data user@server:/backups zip
EOF
}

require_directory() {
    [[ -d "$1" ]] || fail "Not a valid directory: $1"
}

timestamp() {
    date +"$TIMESTAMP_FORMAT"
}

is_remote_destination() {
    [[ "$1" == *:* ]]
}

parse_remote_destination() {
    local remote="$1"
    [[ "$remote" == *:* ]] || fail "Remote destination must be in the form user@host:/path"
    REMOTE_HOST="${remote%%:*}"
    REMOTE_PATH="${remote#*:}"
    [[ -n "$REMOTE_HOST" && -n "$REMOTE_PATH" ]] || fail "Remote destination must be in the form user@host:/path"
}

require_remote_directory() {
    require_command ssh
    ssh "$REMOTE_HOST" "test -d '$REMOTE_PATH'" || fail "Not a valid remote directory: $REMOTE_HOST:$REMOTE_PATH"
}

remote_mkdir() {
    ssh "$REMOTE_HOST" "mkdir -p '$1'"
}

remote_rsync() {
    require_command rsync
    rsync -av -e ssh "$1" "$REMOTE_HOST:$2"
}

remote_scp() {
    require_command scp
    scp "$1" "$REMOTE_HOST:$2"
}

create_zip() {
    local source="$1"
    local zip_path="$2"
    local source_name
    source_name="$(basename "$source")"
    local source_parent
    source_parent="$(cd "$(dirname "$source")" && pwd)"

    mkdir -p "$(dirname "$zip_path")"
    zip_path="$(cd "$(dirname "$zip_path")" && pwd)/$(basename "$zip_path")"
    rm -f "$zip_path"
    (cd "$source_parent" && zip -rq "$zip_path" "$source_name")
}

local_full_copy() {
    local source="$1"
    local destination="$2"
    local backup_path="$destination/$(timestamp)"

    mkdir -p "$destination"
    echo "$backup_path"
    cp -a "$source" "$backup_path"
}

local_zip_copy() {
    local source="$1"
    local destination="$2"
    local zip_path="$destination/$(timestamp).zip"

    mkdir -p "$destination"
    echo "$zip_path"
    create_zip "$source" "$zip_path"
}

local_rsync_copy() {
    local source="$1"
    local destination="$2"

    require_command rsync
    mkdir -p "$destination"
    echo "Syncing ${source%/}/ -> ${destination%/}/"
    rsync -av "${source%/}/" "${destination%/}/"
}

remote_full_copy() {
    local source="$1"
    local remote_dest="$REMOTE_PATH/$(timestamp)"

    remote_mkdir "$remote_dest"
    echo "$REMOTE_HOST:$remote_dest"
    remote_rsync "${source%/}/" "${remote_dest}/"
}

remote_zip_copy() {
    local source="$1"
    local remote_zip="$REMOTE_PATH/$(timestamp).zip"
    local temp_zip

    temp_zip="$(mktemp "${TMPDIR:-/tmp}/backup.XXXXXX.zip")"
    create_zip "$source" "$temp_zip"
    echo "$REMOTE_HOST:$remote_zip"
    remote_scp "$temp_zip" "$remote_zip"
    rm -f "$temp_zip"
}

remote_rsync_copy() {
    local source="$1"

    remote_mkdir "$REMOTE_PATH"
    echo "Syncing ${source%/}/ -> $REMOTE_HOST:${REMOTE_PATH%/}/"
    remote_rsync "${source%/}/" "${REMOTE_PATH%/}/"
}

run_backup() {
    local source="$1"
    local destination="$2"
    local backup_type="$3"
    local remote=false

    if is_remote_destination "$destination"; then
        remote=true
        parse_remote_destination "$destination"
        require_remote_directory
    else
        require_directory "$destination"
    fi

    case "$backup_type" in
        full)
            if $remote; then
                remote_full_copy "$source"
            else
                local_full_copy "$source" "$destination"
            fi
            ;;
        fullzip)
            if $remote; then
                remote_full_copy "$source"
                remote_zip_copy "$source"
            else
                local_full_copy "$source" "$destination"
                local_zip_copy "$source" "$destination"
            fi
            ;;
        zip)
            if $remote; then
                remote_zip_copy "$source"
            else
                local_zip_copy "$source" "$destination"
            fi
            ;;
        rsync)
            if $remote; then
                remote_rsync_copy "$source"
            else
                local_rsync_copy "$source" "$destination"
            fi
            ;;
        *)
            usage
            fail "Invalid backup type: $backup_type"
            ;;
    esac
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi

if [[ $# -lt 3 ]]; then
    usage
    fail "Not enough parameters"
fi

SOURCE="$(cd "$1" && pwd)"
DESTINATION="$2"
BACKUP_TYPE="$3"

require_directory "$SOURCE"

valid_type=false
for type in "${BACKUP_TYPES[@]}"; do
    if [[ "$BACKUP_TYPE" == "$type" ]]; then
        valid_type=true
        break
    fi
done

if ! $valid_type; then
    usage
    fail "Invalid backup type: $BACKUP_TYPE"
fi

require_command zip
run_backup "$SOURCE" "$DESTINATION" "$BACKUP_TYPE"
