(ns lg-yukkuri.graphs.health
  "yukkuri `health` graph — kotoba/RW probe + liveness.

  NSID: com.etzhayyim.apps.yukkuri.health
  Faithful clj port of `lg/lg_yukkuri/graphs/health.py` (ADR-2606280030).

  Topology: START → check_rw → summarize → audit → END.

  DEVIATION (noted): langgraph-clj has no RetryPolicy; the Python `check_rw`
  carried max_attempts=2. The store ping is an INJECTABLE edge (`*rw-ping*`)
  defaulting to an unconfigured store (parity with the Python try/except path)."
  (:require [langgraph.graph :as g]
            [lg-yukkuri.audit :as audit]))

(def ^:dynamic *rw-ping*
  "Default: unconfigured store → {:rw_ok false :error \"rw: store not configured\"}.
  Deployment rebinds to a kotoba `[:find ?e :where [?e :db/ident :db/ident]]` ping."
  (fn [] {:rw_ok false :error "rw: store not configured"}))

(defn- now-iso [] (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss'Z'")
                           (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)))

(defn node-check-rw [_state]
  (try (*rw-ping*)
       (catch Exception e {:rw_ok false :error (str "rw: " (.getMessage e))})))

(defn node-summarize [state]
  {:ok (boolean (:rw_ok state)) :server_now (now-iso)})

(defn node-audit [state]
  (audit/emit-audit-bg {:actor (:app-did (audit/config-from-state state))
                        :activity "yukkuri.health.check"
                        :object-id (str "health:" (quot (System/currentTimeMillis) 1000))
                        :object-type "yukkuri.health"
                        :attributes {:ok (boolean (:ok state)) :rwOk (boolean (:rw_ok state))}})
  {})

(defn build
  "Compile the health StateGraph (check_rw → summarize → audit)."
  []
  (-> (g/state-graph)
      (g/add-node :check_rw node-check-rw)
      (g/add-node :summarize node-summarize)
      (g/add-node :audit node-audit)
      (g/add-edge :check_rw :summarize)
      (g/add-edge :summarize :audit)
      (g/set-entry-point :check_rw)
      (g/set-finish-point :audit)
      (g/compile-graph)))

(def GRAPH (build))
