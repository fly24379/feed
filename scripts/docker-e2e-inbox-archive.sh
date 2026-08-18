#!/usr/bin/env bash
set -euo pipefail

base_url="${BASE_URL:-http://localhost:8080}"
marker="archive-e2e-$(date +%s)"
alice_token=""
dave_token=""
alice_id=""
post_id=""
following_before="false"
policy_before=""
policy_was_explicit="false"
policy_restore_body=""

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

login() {
  curl -fsS -X POST "${base_url}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"demo12345\"}" | jq -r '.accessToken'
}

auth_json() {
  curl -fsS "${base_url}$2" -H "Authorization: Bearer $1"
}

mysql_scalar() {
  docker compose exec -T -e MYSQL_PWD=feed mysql \
    mysql -ufeed -Nse "$1" feed | tr -d '\r'
}

set_follow() {
  curl -fsS -X "$2" "${base_url}/api/relationships/follows/$1" \
    -H "Authorization: Bearer ${dave_token}" >/dev/null
}

set_push_policy() {
  curl -fsS -X PUT "${base_url}/api/admin/fanout-policies/${alice_id}" \
    -H "Authorization: Bearer ${alice_token}" \
    -H 'Content-Type: application/json' \
    -d '{"mode":"PUSH","reason":"archive e2e temporary policy"}' >/dev/null
}

cleanup() {
  set +e
  [[ -n "${post_id}" ]] && curl -fsS -X DELETE "${base_url}/api/posts/${post_id}" \
    -H "Authorization: Bearer ${alice_token}" >/dev/null
  if [[ -n "${alice_id}" && -n "${dave_token}" ]]; then
    set_follow "${alice_id}" "$([[ "${following_before}" == "true" ]] && echo PUT || echo DELETE)"
  fi
  if [[ -n "${alice_id}" && -n "${alice_token}" ]]; then
    if [[ "${policy_was_explicit}" == "true" ]]; then
      curl -fsS -X PUT "${base_url}/api/admin/fanout-policies/${alice_id}" \
        -H "Authorization: Bearer ${alice_token}" \
        -H 'Content-Type: application/json' \
        -d "${policy_restore_body}" >/dev/null
    else
      curl -fsS -X DELETE "${base_url}/api/admin/fanout-policies/${alice_id}" \
        -H "Authorization: Bearer ${alice_token}" >/dev/null
    fi
  fi
}
trap cleanup EXIT

[[ "$(curl -fsS "${base_url}/actuator/health" | jq -r '.status')" == "UP" ]] || fail 'Application health is not UP'
alice_token="$(login demo_alice)"
dave_token="$(login demo_dave)"
[[ -n "${alice_token}" && -n "${dave_token}" ]] || fail 'Demo login failed'

alice_id="$(auth_json "${alice_token}" '/api/users/me' | jq -r '.id')"
following_before="$(auth_json "${dave_token}" "/api/relationships/follows/${alice_id}" | jq -r '.followedByMe')"
policy_before="$(auth_json "${alice_token}" "/api/admin/fanout-policies/${alice_id}")"
policy_was_explicit="$(jq -r '.explicit' <<<"${policy_before}")"
if [[ "${policy_was_explicit}" == "true" ]]; then
  policy_restore_body="$(jq -c '{mode, reason}' <<<"${policy_before}")"
fi

set_follow "${alice_id}" PUT
set_push_policy
post_id="$(curl -fsS -X POST "${base_url}/api/posts" \
  -H "Authorization: Bearer ${alice_token}" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(< /proc/sys/kernel/random/uuid)" \
  -d "{\"content\":\"${marker}\",\"visibility\":\"ALL_FOLLOWERS\",\"targetUserIds\":[]}" | jq -r '.id')"

for _ in $(seq 1 60); do
  [[ "$(mysql_scalar "SELECT status FROM outbox_events WHERE aggregate_id='${post_id}'")" == "PROCESSED" ]] && break
  sleep 0.5
done
[[ "$(mysql_scalar "SELECT status FROM outbox_events WHERE aggregate_id='${post_id}'")" == "PROCESSED" ]] \
  || fail 'Outbox event did not complete'

for _ in $(seq 1 60); do
  [[ "$(mysql_scalar "SELECT COUNT(*) FROM feed_inbox WHERE owner_id=(SELECT id FROM users WHERE username='demo_dave') AND post_id='${post_id}'")" == "1" ]] && break
  sleep 0.5
done
[[ "$(mysql_scalar "SELECT COUNT(*) FROM feed_inbox WHERE owner_id=(SELECT id FROM users WHERE username='demo_dave') AND post_id='${post_id}'")" == "1" ]] \
  || fail 'PUSH post was not written to the hot Inbox'

mysql_scalar "UPDATE posts SET published_at = UTC_TIMESTAMP(6) - INTERVAL 91 DAY WHERE id='${post_id}';
              UPDATE feed_inbox SET published_at = UTC_TIMESTAMP(6) - INTERVAL 91 DAY WHERE post_id='${post_id}'"
expected_archive_rows="$(mysql_scalar "SELECT COUNT(*) FROM feed_inbox WHERE post_id='${post_id}'")"
[[ "${expected_archive_rows}" -gt 0 ]] || fail 'No hot Inbox rows available to archive'

for _ in $(seq 1 60); do
  [[ "$(mysql_scalar "SELECT COUNT(*) FROM feed_inbox_archive WHERE post_id='${post_id}'")" == "${expected_archive_rows}" ]] && break
  sleep 0.5
done
[[ "$(mysql_scalar "SELECT COUNT(*) FROM feed_inbox_archive WHERE post_id='${post_id}'")" == "${expected_archive_rows}" ]] \
  || fail 'Expired Inbox rows were not fully archived'
[[ "$(mysql_scalar "SELECT COUNT(*) FROM feed_inbox WHERE post_id='${post_id}'")" == "0" ]] \
  || fail 'Expired Inbox entry remained in the hot table'
[[ "$(mysql_scalar "SELECT COUNT(*) FROM posts WHERE id='${post_id}'")" == "1" ]] \
  || fail 'Archiving removed the fact post'

if auth_json "${dave_token}" '/api/feed?size=100' | jq -e --arg id "${post_id}" '.items[] | select(.id == $id)' >/dev/null; then
  fail 'Archived Inbox entry remained visible on the homepage'
fi

echo "PASS marker=${marker} archived_post=${post_id}"
