(ns lg-yukkuri.store
  "Injectable persistence seam — clj port of the kotoba-Datomic client surface
  the Python graphs reach through `kotodama.kotoba_datomic.get_kotoba_client`
  (ADR-2606280030).

  The Python nodes call `client.select_where` / `client.insert_row` / `client.q`.
  Per CLAUDE.md the canonical state is the kotoba Datom log and the substrate
  boundary FORBIDS RisingWave; so rather than reproduce psycopg/RW this port
  exposes the three operations as DYNAMIC VARS that default to an unconfigured
  no-op store. The deployment layer rebinds them to a real kotoba `:db-api`
  client (`langchain.kotoba-db/kotoba-api`); tests rebind them to in-memory
  stubs so every graph's topology + transforms verify offline under bb.

  Contract (mirrors the Python kotoba client):
    *select-where* (table col val limit) → vector of row maps (keyword keys)
    *insert-row*   (table row-map)        → the inserted/updated row (upsert)
    *query*        (edn-datalog-string)   → vector of result tuples")

(def ^:dynamic *select-where*
  "Default: unconfigured store → no rows (parity with an unset RW/kotoba conn)."
  (fn [_table _col _val _limit] []))

(def ^:dynamic *insert-row*
  "Default: unconfigured store → no-op upsert returning the row unchanged."
  (fn [_table row] row))

(def ^:dynamic *query*
  "Default: unconfigured store → empty datalog result."
  (fn [_edn] []))

(defn select-where
  "Convenience wrapper with an optional limit (defaults to the Python defaults
  per call-site, so callers pass it explicitly)."
  ([table col val] (*select-where* table col val nil))
  ([table col val limit] (*select-where* table col val limit)))

(defn insert-row [table row] (*insert-row* table row))

(defn query [edn] (*query* edn))
