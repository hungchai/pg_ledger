# SQL increments (SIT / prod hotfixes)

Ordered numbered patches applied **after** base migrations `db/V001`–`db/V003` are already on the writer.

## Rules

- Run files in numeric order on the **writer** (primary), not a replica.
- Do **not** re-run `V001`–`V003` when applying increments.
- Prefer idempotent DDL: `CREATE OR REPLACE`, `IF NOT EXISTS`.
- Never wipe or truncate ledger data.

## Apply

```bash
psql -v ON_ERROR_STOP=1 -f db/increments/001_bank_pool_default_8_max_400.sql
```

Fresh installs that already include the same change in `V002` can skip redundant increments; use them for environments that were migrated before the fix landed.
