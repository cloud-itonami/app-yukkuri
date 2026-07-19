(ns lg-yukkuri.graphs.compose
  "yukkuri `compose` graph — topic → video enqueue (status: queued).

  NSID: com.etzhayyim.apps.yukkuri.compose
  Faithful clj port of `lg/lg_yukkuri/graphs/compose.py` (ADR-2606280030).

  Topology: START → validate → insert → audit → END.

  Creates a vertex_yukkuri_video row (status='queued') via the INJECTABLE
  `store/*insert-row*` seam. The CF Worker onCommit handler picks it up and
  drives generate_script. `insert` short-circuits when `validate` set :error."
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [lg-yukkuri.audit :as audit]
            [lg-yukkuri.store :as store]))

(defn- now-iso [] (str (java.time.OffsetDateTime/now java.time.ZoneOffset/UTC)))

(defn- token-hex [n]
  (let [bs (byte-array n)]
    (.nextBytes (java.security.SecureRandom.) bs)
    (apply str (map #(format "%02x" %) bs))))

(defn node-validate [state]
  (let [topic (str/trim (or (:topic state) ""))]
    (cond
      (str/blank? topic) {:error "topic is required"}
      (> (count topic) 500) {:error "topic too long (max 500 chars)"}
      :else {})))

(defn node-insert [state]
  (if (:error state)
    {}
    (let [{:keys [app-did repo-did]} (audit/config-from-state state)
          topic   (str/trim (or (:topic state) ""))
          outline (let [o (str/trim (or (:outline state) ""))] (when (seq o) o))
          owner   (or (:owner_did state) app-did)
          rkey    (str "video-" (token-hex 6))
          vid     (str "at://" repo-did "/com.etzhayyim.apps.yukkuri.video/" rkey)
          created (now-iso)
          title   (str "ゆっくり実況: " (subs topic 0 (min 48 (count topic))))]
      (try
        (store/insert-row "vertex_yukkuri_video"
                          {:vertex_id vid :video_id rkey :repo repo-did :owner_did owner
                           :title title :topic topic :outline outline
                           :status "queued" :created_at created})
        {:video_id rkey :video_uri vid}
        (catch Exception e {:error (str "insert: " (.getMessage e))})))))

(defn node-audit [state]
  (audit/emit-audit-bg {:actor (:app-did (audit/config-from-state state))
                        :activity "yukkuri.compose"
                        :object-id (str "video:" (or (:video_id state) "") ":" (quot (System/currentTimeMillis) 1000))
                        :object-type "yukkuri.video"
                        :attributes {:videoId (:video_id state)
                                     :topic (let [t (or (:topic state) "")] (subs t 0 (min 100 (count t))))
                                     :ok (not (boolean (:error state)))}})
  {})

(defn build
  "Compile the compose StateGraph (validate → insert → audit)."
  []
  (-> (g/state-graph)
      (g/add-node :validate node-validate)
      (g/add-node :insert node-insert)
      (g/add-node :audit node-audit)
      (g/add-edge :validate :insert)
      (g/add-edge :insert :audit)
      (g/set-entry-point :validate)
      (g/set-finish-point :audit)
      (g/compile-graph)))

(def GRAPH (build))
