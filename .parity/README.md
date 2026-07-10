# stdnum-clj parity watcher

`.parity/` stores the local type snapshot and the upstream python-stdnum commit that has already been reconciled. The weekly GitHub Actions watcher compares the current `arthurdejong/python-stdnum` master tree against those files and posts one rolling GitHub issue.

Files:

- `types.edn` — snapshot of `stdnum.core/types` (the guard test `parity_snapshot_test` fails if it drifts).
- `upstream-sha` — last upstream master commit reconciled against; the watcher diffs changed modules from here.
- `aliases.edn` — rename map `{upstream-kw our-kw}`. stdnum-clj localizes many names (`it/iva.py` → `:it-vat`, `us/rtn.py` → `:aba`, `iso9362.py` → `:bic`, python's `in_`/`is_` reserved-word dirs → `:in`/`:is`). Without it the report shows ~55 renames as false gaps. Add a pair when you port a type under a different name than upstream's module.
- `ignore.edn` — upstream modules intentionally not mirrored (inlined checksum algorithms, generic `:ean`). A genuinely-missing type belongs in the report, not here.
- `report.md` — generated each run (gitignored).

The watcher warns under **Stale aliases** if an alias source is no longer an upstream module or a target is no longer one of our types — fix `aliases.edn` when that fires.

Regenerate `types.edn` only after `stdnum.core/types` intentionally changes:

```sh
lein run -m clojure.main <<'EOF'
(require '[clojure.pprint :refer [pprint]]
         '[stdnum.core :as stdnum])
(spit ".parity/types.edn"
      (with-out-str
        (pprint (vec (sort (map (comp keyword name) stdnum/types))))))
EOF
```

Reconcile loop:

1. Run `bb .parity/parity_watch.clj`.
2. Review `.parity/report.md`.
3. Port missing modules and re-verify vectors for changed existing types.
4. After reconciliation, bump `.parity/upstream-sha` to the current upstream master SHA printed in the report footer.
