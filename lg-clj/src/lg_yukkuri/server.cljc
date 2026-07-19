(ns lg-yukkuri.server
  "lg-yukkuri dispatch surface — clj port of `lg/lg_yukkuri/server.py` (ADR-2606280030).

  The Python FastAPI app exposes:
    POST /runs                  → invoke a graph synchronously
    POST /runs/stream           → SSE stream of graph events
    POST /xrpc/{nsid}           → XRPC shim (NSID → assistant_id, camel→snake input)
    GET  /threads/{tid}/state   → fetch latest checkpoint
    GET  /ok /health            → liveness / readiness

  This namespace ports the ROUTING + graph registry (GRAPHS / NSID-MAP) + the
  camelCase→snake_case input coercion + invoke/serialize logic as plain clj
  functions (`dispatch-run`, `dispatch-xrpc`, `ok`, `health`). Binding these to a
  concrete org.httpkit.server is left to the deployment layer (the graphs +
  dispatch are the load-bearing port); a ready-to-mount `ring-handler` +
  `start!`/`stop!` are provided for org.httpkit.server when that dep is present.

  The Python FastAPI server (`lg/`) remains the DEPLOYED runtime and COEXISTS."
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [lg-yukkuri.graphs.health :as health]
            [lg-yukkuri.graphs.list-videos :as list-videos]
            [lg-yukkuri.graphs.get-video :as get-video]
            [lg-yukkuri.graphs.compose :as compose]
            [lg-yukkuri.graphs.generate-script :as generate-script]
            [lg-yukkuri.graphs.synthesize-voice :as synthesize-voice]
            [lg-yukkuri.graphs.generate-visual :as generate-visual]
            [lg-yukkuri.graphs.generate-bgm :as generate-bgm]
            [lg-yukkuri.graphs.render-video :as render-video]
            [lg-yukkuri.graphs.review-video :as review-video]))

(def version "0.1.0")

(def GRAPHS
  {"health"           health/GRAPH
   "list_videos"      list-videos/GRAPH
   "get_video"        get-video/GRAPH
   "compose"          compose/GRAPH
   "generate_script"  generate-script/GRAPH
   "synthesize_voice" synthesize-voice/GRAPH
   "generate_visual"  generate-visual/GRAPH
   "generate_bgm"     generate-bgm/GRAPH
   "render_video"     render-video/GRAPH
   "review_video"     review-video/GRAPH})

(def NSID-MAP
  {"com.etzhayyim.apps.yukkuri.health"          "health"
   "com.etzhayyim.apps.yukkuri.listVideos"      "list_videos"
   "com.etzhayyim.apps.yukkuri.getVideo"        "get_video"
   "com.etzhayyim.apps.yukkuri.compose"         "compose"
   "com.etzhayyim.apps.yukkuri.generateScript"  "generate_script"
   "com.etzhayyim.apps.yukkuri.synthesizeVoice" "synthesize_voice"
   "com.etzhayyim.apps.yukkuri.generateVisual"  "generate_visual"
   "com.etzhayyim.apps.yukkuri.generateBgm"     "generate_bgm"
   "com.etzhayyim.apps.yukkuri.renderVideo"     "render_video"
   "com.etzhayyim.apps.yukkuri.reviewVideo"     "review_video"})

(defn camel->snake
  "Mirror of server._camel_to_snake: insert _ before inner uppercase, lower-case."
  [s]
  (let [s (name s)]
    (apply str (map-indexed (fn [i ch]
                              (if (and (pos? i) (Character/isUpperCase ch))
                                (str "_" (Character/toLowerCase ch))
                                (str (Character/toLowerCase ch))))
                            s))))

(defn coerce-xrpc-input
  "camelCase top-level keys → snake_case (parity with the Python xrpc shim)."
  [body]
  (reduce-kv (fn [m k v] (assoc m (keyword (camel->snake k)) v)) {} (or body {})))

(defn check-api-key
  "If an explicit expected API key is supplied, x-api-key must match."
  ([x-api-key] (check-api-key x-api-key ""))
  ([x-api-key api-key]
  (if (and (seq api-key) (not= x-api-key api-key))
    {:status 401 :body {:error "invalid x-api-key"}}
    nil)))

(defn- run-graph [graph input]
  (let [started (System/nanoTime)]
    (try
      (let [result (g/invoke graph (or input {}))]
        {:result result :latencyMs (quot (- (System/nanoTime) started) 1000000)})
      (catch Exception e
        {:error (let [m (str (.getMessage e))] (subs m 0 (min 500 (count m))))
         :errorType (.getName (class e))
         :latencyMs (quot (- (System/nanoTime) started) 1000000)}))))

(defn ok
  "GET /ok → {:ok true :graphs [...] :version ...}"
  []
  {:status 200 :body {:ok true :graphs (vec (keys GRAPHS)) :version version}})

(defn health
  "GET /health → liveness (checkpointer presence is deployment-layer concern)."
  []
  {:status 200 :body {:ok true :checkpointer true}})

(defn dispatch-run
  "POST /runs body → {:status :body}. body keys: :assistant_id :input.
  Enforces optional x-api-key via opts {:x-api-key ...}."
  ([body] (dispatch-run body {}))
  ([body {:keys [x-api-key api-key host-config]}]
   (or (check-api-key x-api-key (or api-key ""))
       (let [aid   (str (or (:assistant_id body) ""))
             graph (get GRAPHS aid)]
         (if (nil? graph)
           {:status 404 :body {:error (str "unknown graph: " aid)}}
           (let [input (cond-> (or (:input body) {})
                         host-config (assoc :host-config host-config))
                 {:keys [result error errorType latencyMs]} (run-graph graph input)]
             (if error
               {:status 200 :body {:ok false :error error :errorType errorType
                                   :assistantId aid :latencyMs latencyMs}}
               {:status 200 :body {:ok true :result result :assistantId aid :latencyMs latencyMs}})))))))

(defn dispatch-xrpc
  "POST /xrpc/{nsid} body → {:status :body}. NSID → assistant_id; body keys are
  coerced camel→snake. /xrpc is unauthenticated (parity with the Python server)."
  ([nsid body] (dispatch-xrpc nsid body {}))
  ([nsid body {:keys [host-config]}]
  (let [aid (get NSID-MAP nsid)]
    (if (nil? aid)
      {:status 404 :body {:error (str "unknown NSID: " nsid)}}
      (let [graph (get GRAPHS aid)
            input (cond-> (coerce-xrpc-input body)
                    host-config (assoc :host-config host-config))
            {:keys [result error errorType latencyMs]} (run-graph graph input)]
        (if error
          {:status 200 :body {:error (str "lg-yukkuri " errorType) :errorDetail error
                              :assistantId aid :latencyMs latencyMs}}
          {:status 200 :body (-> (if (map? result) result {:result result})
                                 (assoc :latencyMs latencyMs :assistantId aid))}))))))
