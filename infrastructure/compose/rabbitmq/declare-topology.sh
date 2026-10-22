#!/bin/sh
# RabbitMQ topology for Phase 4 (CDC), Task 4.1.
#
# Declares only the objects the platform owns. `tigerbeetle.internal.locker.*`
# and `tigerbeetle.internal.progress.*` are declared by the `tigerbeetle amqp`
# CDC job itself (Task 4.2) and are never touched here — see README.md in this
# directory for why the names differ from earlier drafts of this task.
#
# Idempotent: reapplying with the same arguments is a no-op (RabbitMQ answers
# 204/201). Reapplying with DIFFERENT arguments is a hard failure (RabbitMQ
# answers 400 "inequivalent arg") — this script does not catch or retry that,
# so a topology drift fails the deploy instead of silently coexisting.
set -eu

: "${RABBITMQ_MANAGEMENT_URL:=http://rabbitmq:15672}"
: "${RABBITMQ_DEFAULT_USER:?RABBITMQ_DEFAULT_USER is required}"
: "${RABBITMQ_DEFAULT_PASS:?RABBITMQ_DEFAULT_PASS is required}"
: "${RABBITMQ_VHOST:=/}"
: "${RABBITMQ_BRIDGE_LEDGER_DELIVERY_LIMIT:=5}"

# RFC 3986 path-encode the vhost the way the management API requires: "/"
# becomes %2F. This deployment only ever uses the default vhost, but the
# encoding is correct for any single-segment vhost name.
vhost_encoded=$(printf '%s' "$RABBITMQ_VHOST" | sed 's#/#%2F#g')

response_body="$(mktemp)"
trap 'rm -f "$response_body"' EXIT

# declare_object METHOD PATH BODY DESCRIPTION
declare_object() {
  method="$1"
  path="$2"
  body="$3"
  description="$4"

  status=$(curl -sS -o "$response_body" -w '%{http_code}' \
    -u "${RABBITMQ_DEFAULT_USER}:${RABBITMQ_DEFAULT_PASS}" \
    -X "$method" \
    -H 'content-type: application/json' \
    -d "$body" \
    "${RABBITMQ_MANAGEMENT_URL}${path}")

  case "$status" in
    200 | 201 | 204)
      echo "OK   ($status) $description"
      ;;
    *)
      echo "FAIL ($status) $description" >&2
      cat "$response_body" >&2
      echo >&2
      exit 1
      ;;
  esac
}

# The CDC job's publish target (phase-04-cdc.md Task 4.1). Must exist before
# `tigerbeetle amqp` starts (Task 4.2) — the job does not declare it itself.
# Fanout: the only bound queue is bridge.ledger, so routing keys add nothing.
declare_object PUT "/api/exchanges/${vhost_encoded}/tigerbeetle" \
  '{"type":"fanout","durable":true,"auto_delete":false,"internal":false,"arguments":{}}' \
  "exchange tigerbeetle (fanout, durable)"

# Declared before bridge.ledger: the latter's dead-letter-routing-key names
# this queue via the default exchange, so it must already exist to receive
# anything that exceeds the delivery limit below.
declare_object PUT "/api/queues/${vhost_encoded}/bridge.ledger.dlq" \
  '{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"quorum"}}' \
  "queue bridge.ledger.dlq (quorum, durable)"

# The bridge's only input (Task 4.3). x-delivery-limit is the quorum queue's
# built-in poison-message handling: a message redelivered more than this many
# times is dead-lettered automatically, no consumer-side retry counting
# needed. x-dead-letter-exchange="" (explicit, not omitted — RabbitMQ rejects
# x-dead-letter-routing-key with no x-dead-letter-exchange at all as
# "routing_key_but_no_dlx_defined") routes through the default exchange,
# which delivers by queue name — no separate dead-letter exchange to declare.
declare_object PUT "/api/queues/${vhost_encoded}/bridge.ledger" \
  "{\"durable\":true,\"auto_delete\":false,\"arguments\":{\"x-queue-type\":\"quorum\",\"x-dead-letter-exchange\":\"\",\"x-dead-letter-routing-key\":\"bridge.ledger.dlq\",\"x-delivery-limit\":${RABBITMQ_BRIDGE_LEDGER_DELIVERY_LIMIT}}}" \
  "queue bridge.ledger (quorum, durable, delivery-limit=${RABBITMQ_BRIDGE_LEDGER_DELIVERY_LIMIT})"

declare_object POST "/api/bindings/${vhost_encoded}/e/tigerbeetle/q/bridge.ledger" \
  '{"routing_key":"","arguments":{}}' \
  "binding tigerbeetle -> bridge.ledger"

echo "RabbitMQ topology applied."
