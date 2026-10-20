#!/bin/bash
# Runs once when the database cluster is created (PRD-0003 SK-9, SK-15): the roles of the platform and Keycloak.
# slg_migrator owns the platform database and its schema (DDL, Flyway only); slg_app has no DDL rights and gets its
# table privileges from the migrations. Passwords come from Docker secret files and are passed as psql variables.
set -euo pipefail

app_pw="$(tr -d '\n' < /run/secrets/db_app_password)"
migrator_pw="$(tr -d '\n' < /run/secrets/db_migrator_password)"
keycloak_pw="$(tr -d '\n' < /run/secrets/kc_db_password)"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v app_pw="$app_pw" -v migrator_pw="$migrator_pw" -v keycloak_pw="$keycloak_pw" <<'SQL'
CREATE ROLE slg_migrator LOGIN PASSWORD :'migrator_pw';
CREATE ROLE slg_app LOGIN PASSWORD :'app_pw';
CREATE ROLE keycloak LOGIN PASSWORD :'keycloak_pw';
CREATE DATABASE platform OWNER slg_migrator;
CREATE DATABASE keycloak OWNER keycloak;
REVOKE CONNECT ON DATABASE platform FROM PUBLIC;
REVOKE CONNECT ON DATABASE keycloak FROM PUBLIC;
GRANT CONNECT ON DATABASE platform TO slg_app, slg_migrator;
GRANT CONNECT ON DATABASE keycloak TO keycloak;
SQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname platform \
  -c "ALTER SCHEMA public OWNER TO slg_migrator"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname keycloak \
  -c "ALTER SCHEMA public OWNER TO keycloak"
