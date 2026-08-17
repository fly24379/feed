#!/usr/bin/env bash
set -euo pipefail

base_url="${BASE_URL:-http://localhost:8080}"
marker="docker-e2e-$(date +%s)"
alice_token=""
dave_token=""
frank_token=""
alice_id=""
dave_id=""
frank_id=""
push_backfill_post=""
push_kafka_post=""
pull_post=""
alice_initial_follow="false"
frank_initial_follow="false"
follows_captured="false"
alice_policy_mutated="false"
frank_policy_mutated="false"

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
  local token="$1"
  local path="$2"
  curl -fsS "${base_url}${path}" -H "Authorization: Bearer ${token}"
}

mysql_scalar() {
  docker compose exec -T -e MYSQL_PWD=feed mysql \
    mysql -ufeed -Nse "$1" feed | tr -d '\r'
}

wait_for_outbox() {
  local post_id="$1"
  local status=""
  for _ in $(seq 1 30); do
    status="$(mysql_scalar "SELECT status FROM outbox_events WHERE aggregate_id='${post_id}' AND event_type='POST_PUBLISHED'")"
    [[ "$status" == "PROCESSED" ]] && return 0
    sleep 0.5
  done
  fail "Outbox event for ${post_id} did not reach PROCESSED (last=${status})"
}

wait_for_feed_post() {
  local token="$1"
  local post_id="$2"
  for _ in $(seq 1 30); do
    if auth_json "$token" '/api/feed?size=100' | jq -e --arg id "$post_id" '.items[] | select(.id == $id)' >/dev/null; then
      return 0
    fi
    sleep 0.5
  done
  fail "Post ${post_id} did not appear in feed"
}

publish() {
  local token="$1"
  local content="$2"
  local key
  key="$(< /proc/sys/kernel/random/uuid)"
  curl -fsS -X POST "${base_url}/api/posts" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ${key}" \
    -d "{\"content\":\"${content}\",\"visibility\":\"ALL_FOLLOWERS\",\"targetUserIds\":[]}" | jq -r '.id'
}

set_policy() {
  local author_id="$1"
  local mode="$2"
  curl -fsS -X PUT "${base_url}/api/admin/fanout-policies/${author_id}" \
    -H "Authorization: Bearer ${alice_token}" \
    -H 'Content-Type: application/json' \
    -d "{\"mode\":\"${mode}\",\"reason\":\"docker e2e temporary policy\"}" >/dev/null
}

set_follow() {
  local target_id="$1"
  local method="$2"
  curl -fsS -X "$method" "${base_url}/api/relationships/follows/${target_id}" \
    -H "Authorization: Bearer ${dave_token}"
}

cleanup() {
  set +e
  [[ -n "$push_backfill_post" ]] && curl -fsS -X DELETE "${base_url}/api/posts/${push_backfill_post}" -H "Authorization: Bearer ${alice_token}" >/dev/null
  [[ -n "$push_kafka_post" ]] && curl -fsS -X DELETE "${base_url}/api/posts/${push_kafka_post}" -H "Authorization: Bearer ${alice_token}" >/dev/null
  [[ -n "$pull_post" ]] && curl -fsS -X DELETE "${base_url}/api/posts/${pull_post}" -H "Authorization: Bearer ${frank_token}" >/dev/null
  if [[ "$follows_captured" == "true" && -n "$alice_id" ]]; then
    set_follow "$alice_id" "$([[ "$alice_initial_follow" == "true" ]] && echo PUT || echo DELETE)" >/dev/null
  fi
  if [[ "$alice_policy_mutated" == "true" && -n "$alice_id" ]]; then
    curl -fsS -X DELETE "${base_url}/api/admin/fanout-policies/${alice_id}" -H "Authorization: Bearer ${alice_token}" >/dev/null
  fi
  if [[ "$follows_captured" == "true" && -n "$frank_id" ]]; then
    set_follow "$frank_id" "$([[ "$frank_initial_follow" == "true" ]] && echo PUT || echo DELETE)" >/dev/null
  fi
  if [[ "$frank_policy_mutated" == "true" && -n "$frank_id" ]]; then
    curl -fsS -X DELETE "${base_url}/api/admin/fanout-policies/${frank_id}" -H "Authorization: Bearer ${alice_token}" >/dev/null
  fi
}
trap cleanup EXIT

[[ "$(curl -fsS "${base_url}/actuator/health" | jq -r '.status')" == "UP" ]] || fail 'Application health is not UP'
alice_token="$(login demo_alice)"
dave_token="$(login demo_dave)"
frank_token="$(login demo_frank)"
[[ -n "$alice_token" && -n "$dave_token" && -n "$frank_token" ]] || fail 'Demo login failed'

alice_id="$(auth_json "$alice_token" '/api/users/me' | jq -r '.id')"
dave_id="$(auth_json "$dave_token" '/api/users/me' | jq -r '.id')"
frank_id="$(auth_json "$frank_token" '/api/users/me' | jq -r '.id')"
alice_initial_follow="$(auth_json "$dave_token" "/api/relationships/follows/${alice_id}" | jq -r '.followedByMe')"
frank_initial_follow="$(auth_json "$dave_token" "/api/relationships/follows/${frank_id}" | jq -r '.followedByMe')"
follows_captured="true"

alice_policy="$(auth_json "$alice_token" "/api/admin/fanout-policies/${alice_id}")"
frank_policy="$(auth_json "$alice_token" "/api/admin/fanout-policies/${frank_id}")"
[[ "$(jq -r '.explicit' <<<"$alice_policy")" == "false" ]] || fail 'Alice already has an explicit fanout policy; refusing to overwrite it'
[[ "$(jq -r '.explicit' <<<"$frank_policy")" == "false" ]] || fail 'Frank already has an explicit fanout policy; refusing to overwrite it'

set_follow "$alice_id" DELETE >/dev/null
set_follow "$frank_id" DELETE >/dev/null
set_policy "$alice_id" PUSH
alice_policy_mutated="true"
set_policy "$frank_id" PULL
frank_policy_mutated="true"

push_backfill_post="$(publish "$alice_token" "${marker} push history backfill")"
[[ "$(mysql_scalar "SELECT delivery_mode FROM posts WHERE id='${push_backfill_post}'")" == "PUSH" ]] || fail 'Expected PUSH delivery mode'
if auth_json "$dave_token" '/api/feed?size=100' | jq -e --arg id "$push_backfill_post" '.items[] | select(.id == $id)' >/dev/null; then
  fail 'Unfollowed viewer saw PUSH post'
fi

follow_response="$(set_follow "$alice_id" PUT)"
[[ "$(jq -r '.followedByMe' <<<"$follow_response")" == "true" ]] || fail 'Follow state did not become true'
[[ "$(jq -r '.backfilledPosts' <<<"$follow_response")" -ge 1 ]] || fail 'PUSH history was not backfilled on follow'
wait_for_feed_post "$dave_token" "$push_backfill_post"

push_kafka_post="$(publish "$alice_token" "${marker} push kafka fanout")"
wait_for_outbox "$push_kafka_post"
wait_for_feed_post "$dave_token" "$push_kafka_post"
[[ "$(mysql_scalar "SELECT COUNT(*) FROM feed_inbox WHERE owner_id=${dave_id} AND post_id='${push_kafka_post}'")" == "1" ]] || fail 'PUSH post was not written to follower inbox'

set_follow "$frank_id" PUT >/dev/null
pull_post="$(publish "$frank_token" "${marker} pull timeline merge")"
[[ "$(mysql_scalar "SELECT delivery_mode FROM posts WHERE id='${pull_post}'")" == "PULL" ]] || fail 'Expected PULL delivery mode'
wait_for_outbox "$pull_post"
wait_for_feed_post "$dave_token" "$pull_post"
[[ "$(mysql_scalar "SELECT COUNT(*) FROM feed_inbox WHERE owner_id=${dave_id} AND post_id='${pull_post}'")" == "0" ]] || fail 'PULL post unexpectedly wrote follower inbox'

following_page="$(auth_json "$dave_token" '/api/relationships/following?size=100')"
jq -e --argjson alice "$alice_id" --argjson frank "$frank_id" \
  '([.items[].id] | index($alice)) != null and ([.items[].id] | index($frank)) != null' \
  <<<"$following_page" >/dev/null || fail 'Following page did not contain both authors'

set_follow "$alice_id" DELETE >/dev/null
set_follow "$frank_id" DELETE >/dev/null
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "${base_url}/api/posts/${push_kafka_post}" -H "Authorization: Bearer ${dave_token}")" == "403" ]] || fail 'Unfollow did not revoke PUSH post access'
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "${base_url}/api/posts/${pull_post}" -H "Authorization: Bearer ${dave_token}")" == "403" ]] || fail 'Unfollow did not revoke PULL post access'

echo "PASS marker=${marker} push_backfill=${push_backfill_post} push_kafka=${push_kafka_post} pull=${pull_post}"
