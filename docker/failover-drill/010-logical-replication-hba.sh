#!/bin/bash
set -euo pipefail

# Logical replication uses a replication-protocol connection, which the
# image's default "host all" rule does not cover.
printf '%s\n' "host replication all all scram-sha-256" >> "${PGDATA}/pg_hba.conf"
printf '%s\n' "host all all all scram-sha-256" >> "${PGDATA}/pg_hba.conf"
