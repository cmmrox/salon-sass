#!/usr/bin/env bash
# Creates salons in the production-like stack, as the owning role. Nothing else ever creates one
# there: the stack has no automatic seed.
#
# Usage:
#   infra/scripts/stack-seed.sh                               the three synthetic salons of
#                                                             docs/qa/test-plan.md (alpha, beta and
#                                                             gamma), with their fixed ids; running it
#                                                             again changes nothing
#   infra/scripts/stack-seed.sh add <slug> [name] [colour]    one more active salon, for a test, with
#                                                             a new id; refused when the slug is taken,
#                                                             reserved or not a slug
#
# The synthetic salons come from the same file as the local environment's seed
# (apps/api/src/main/resources/db/local/afterMigrate.sql).
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/stack.sh"

require_docker
load_stack_settings
ensure_deployed_file
container_running postgres || fail "the stack's database is not running. Start the stack first: infra/scripts/stack-up.sh"

case "${1:-}" in
  '')
    psql_as_owner --file - <"$ROOT/apps/api/src/main/resources/db/local/afterMigrate.sql" \
      || fail "the synthetic salons could not be created"
    say "salons: alpha, beta and gamma ($(stack_public_url "<slug>.${SALON_PLATFORM_DOMAIN}"))"
    ;;
  add)
    slug="${2:-}"
    [ -n "$slug" ] || fail "usage: infra/scripts/stack-seed.sh add <slug> [name] [colour]"
    name="${3:-Salon ${slug}}"
    colour="${4:-#2563EB}"
    [[ "$colour" =~ ^#[0-9A-Fa-f]{6}$ ]] || fail "'$colour' is not a colour such as #2563EB"
    # The database refuses a slug of the wrong form, a reserved one and one that is taken; psql then
    # stops with the database's reason and a non-zero exit.
    psql_as_owner --set slug="$slug" --set name="$name" --set colour="$colour" --file - <<'SQL' \
      || fail "salon '$slug' could not be created (see above)"
\set QUIET on
BEGIN;
INSERT INTO tenant (id, slug, status) VALUES (gen_random_uuid(), :'slug', 'active');
-- The brand row is written with the salon set, so that it passes row-level security.
SELECT set_config('app.tenant_id', id::text, true) FROM tenant WHERE slug = :'slug' \gset
INSERT INTO salon_brand (tenant_id, display_name, brand_colour)
  SELECT id, :'name', :'colour' FROM tenant WHERE slug = :'slug';
-- Its default branch and empty profile drafts (F04-S01), through branding's own function.
SELECT branding_add_default_branch(id, left(:'name', 120), now()) FROM tenant WHERE slug = :'slug' \gset
COMMIT;
SQL
    say "salon: ${slug} ($(stack_public_url "${slug}.${SALON_PLATFORM_DOMAIN}"))"
    ;;
  *)
    fail "usage: infra/scripts/stack-seed.sh [add <slug> [name] [colour]]"
    ;;
esac
