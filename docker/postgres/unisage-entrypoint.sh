#!/bin/sh
# UNISAGE-102 (ADR-0010): entrypoint of the unisage-db container. It runs the image's own
# docker-entrypoint.sh as a child instead of exec'ing it, so the container outlives a Postgres
# stop. That is what lets the backend restore a physical backup without access to Docker:
#
#   1. backend-java downloads the backup into /restore/jobs/<id>/ (full/, diff/, wal/, target)
#      and then creates /restore/jobs/<id>/READY;
#   2. this script sees READY, stops Postgres (fast shutdown), moves the current data aside,
#      rebuilds PGDATA from the backup (pg_combinebackup for a Differential) and starts Postgres
#      in recovery up to the requested target;
#   3. once Postgres leaves recovery the job is SUCCEEDED and the old data is dropped; if
#      Postgres stops during recovery the old data is put back and the job is FAILED.
#
# Progress goes to /restore/jobs/<id>/status: the phase on line 1, a message on line 2.
set -u

RESTORE_DIR=/restore
JOBS_DIR="$RESTORE_DIR/jobs"
PGDATA="${PGDATA:-/var/lib/postgresql/data}"
WORK_DIR=/var/lib/postgresql/restore-work
PREVIOUS_DIR=/var/lib/postgresql/restore-previous

mkdir -p /wal-archive "$JOBS_DIR"
chown postgres:postgres /wal-archive
# backend-java writes the jobs under its own user.
chmod 0777 "$JOBS_DIR"
# Tells the backend this container can take a restore (RestoreJobs.isAvailable).
echo 1 > "$RESTORE_DIR/.unisage-entrypoint"

log() {
    echo "unisage-entrypoint: $*" >&2
}

write_status() {
    printf '%s\n%s\n' "$2" "$3" > "$1/status.tmp" && mv "$1/status.tmp" "$1/status"
}

phase_of() {
    [ -f "$1/status" ] && head -n 1 "$1/status" || true
}

# A READY job this script has not taken yet (no status, or the backend's own PREPARING one).
pending_job() {
    for ready in "$JOBS_DIR"/*/READY; do
        [ -f "$ready" ] || continue
        job=$(dirname "$ready")
        case "$(phase_of "$job")" in
            ""|PREPARING) echo "$job"; return ;;
        esac
    done
}

# Unpacks one pg_basebackup -Ft -z backup into a plain directory.
unpack() {
    mkdir -p "$2/pg_wal"
    tar -xzf "$1/base.tar.gz" -C "$2" || return 1
    if [ -f "$1/pg_wal.tar.gz" ]; then
        tar -xzf "$1/pg_wal.tar.gz" -C "$2/pg_wal" || return 1
    fi
    cp "$1/backup_manifest" "$2/backup_manifest"
}

move_contents() {
    find "$1" -mindepth 1 -maxdepth 1 -exec mv -t "$2" {} +
}

# Rebuilds PGDATA from the job's backup. On any error PGDATA is left as it was.
apply_job() {
    job=$1
    log "applying restore job $job"
    write_status "$job" APPLYING "Đang giải nén bản sao lưu"
    rm -rf "$WORK_DIR" && mkdir -p "$WORK_DIR"
    if ! unpack "$job/full" "$WORK_DIR/full"; then
        write_status "$job" FAILED "Không giải nén được bản Full"
        return 1
    fi
    restored="$WORK_DIR/full"
    if [ -d "$job/diff" ]; then
        if ! unpack "$job/diff" "$WORK_DIR/diff"; then
            write_status "$job" FAILED "Không giải nén được bản Differential"
            return 1
        fi
        chown -R postgres:postgres "$WORK_DIR"
        write_status "$job" APPLYING "Đang ghép bản Full với bản Differential"
        if ! gosu postgres pg_combinebackup "$WORK_DIR/full" "$WORK_DIR/diff" -o "$WORK_DIR/combined" >"$WORK_DIR/combine.log" 2>&1; then
            write_status "$job" FAILED "pg_combinebackup lỗi: $(tail -n 1 "$WORK_DIR/combine.log")"
            return 1
        fi
        restored="$WORK_DIR/combined"
    fi

    # "immediate" (stop once the backup is consistent) or a UTC time such as 2026-10-13 03:14:00+00.
    target=$(head -n 1 "$job/target")
    if [ "$target" = immediate ]; then
        target_setting="recovery_target = 'immediate'"
    elif printf '%s' "$target" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\+00$'; then
        target_setting="recovery_target_time = '$target'"
    else
        write_status "$job" FAILED "Thời điểm khôi phục không hợp lệ"
        return 1
    fi
    # Settings left by an earlier restore would otherwise apply again.
    sed -i '/^restore_command\|^recovery_target/d' "$restored/postgresql.auto.conf"
    cat >> "$restored/postgresql.auto.conf" <<EOF
restore_command = 'cp "$job/wal/%f" "%p"'
recovery_target_timeline = 'latest'
$target_setting
recovery_target_action = 'promote'
EOF
    touch "$restored/recovery.signal"

    write_status "$job" APPLYING "Đang thay dữ liệu hiện tại bằng bản sao lưu"
    rm -rf "$PREVIOUS_DIR" && mkdir -p "$PREVIOUS_DIR"
    move_contents "$PGDATA" "$PREVIOUS_DIR"
    move_contents "$restored" "$PGDATA"
    chown -R postgres:postgres "$PGDATA"
    chmod 0700 "$PGDATA"
    rm -rf "$WORK_DIR"
    write_status "$job" RECOVERING "Postgres đang phát lại nhật ký giao dịch"
}

roll_back() {
    log "restore failed, putting the previous data back"
    find "$PGDATA" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
    move_contents "$PREVIOUS_DIR" "$PGDATA"
    rm -rf "$PREVIOUS_DIR"
}

finish_job() {
    rm -rf "$PREVIOUS_DIR" "$1/full" "$1/diff" "$1/wal"
    write_status "$1" SUCCEEDED "Khôi phục xong"
    log "restore job $1 succeeded"
}

in_recovery() {
    gosu postgres psql -h /var/run/postgresql -d postgres -Atc 'SELECT pg_is_in_recovery()' 2>/dev/null
}

pg_pid=""
stopping=0
on_signal() {
    stopping=1
    [ -n "$pg_pid" ] && kill -INT "$pg_pid" 2>/dev/null
}
trap on_signal TERM INT

while :; do
    job=$(pending_job)
    if [ -n "$job" ] && ! apply_job "$job"; then
        log "restore job $job was not applied"
        job=""
    fi

    docker-entrypoint.sh "$@" &
    pg_pid=$!
    restart=0
    while kill -0 "$pg_pid" 2>/dev/null; do
        if [ -n "$job" ] && [ "$(in_recovery)" = "f" ]; then
            finish_job "$job"
            job=""
        fi
        if [ -z "$job" ] && [ "$stopping" = 0 ] && [ -n "$(pending_job)" ]; then
            log "restore requested, stopping Postgres"
            restart=1
            kill -INT "$pg_pid"
        fi
        sleep 2 &
        wait $! 2>/dev/null
    done
    wait "$pg_pid"
    code=$?
    pg_pid=""

    if [ -n "$job" ]; then
        roll_back
        write_status "$job" FAILED "Postgres dừng khi đang khôi phục (thường do thời điểm khôi phục vượt quá nhật ký giao dịch đã sao lưu). Đã quay lại dữ liệu trước khi khôi phục."
        [ "$stopping" = 1 ] && exit "$code"
        continue
    fi
    if [ "$stopping" = 1 ] || [ "$restart" = 0 ]; then
        exit "$code"
    fi
done
