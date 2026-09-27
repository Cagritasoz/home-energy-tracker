#!/usr/bin/env sh

# set -e: stop at the first failure rather than silently creating some topics and skipping others.
set -e

BOOTSTRAP_SERVER="${KAFKA_BOOTSTRAP_SERVER:-kafka-broker-controller-1:9092}"

# One function, one topic per call - adding a topic later is one more line at the bottom, not a
# copy-pasted block. --if-not-exists makes every call idempotent: safe to run this whole script
# again against a broker that already has these topics (e.g. a `docker compose up` after a
# container restart that didn't wipe the broker's own volume).
create_topic() {
    topic_name="$1" # Capture method arguments.
    partitions="$2"
    replication_factor="$3"

    echo "Creating topic '$topic_name' (partitions=$partitions, replication-factor=$replication_factor) if it doesn't already exist..."
    /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server "$BOOTSTRAP_SERVER" \
        --create \
        --if-not-exists \
        --topic "$topic_name" \
        --partitions "$partitions" \
        --replication-factor "$replication_factor"
}

create_topic "user.events.v1" 3 1

echo "Done."
