(ns lg-yukkuri.graphs.list-videos
  "yukkuri `listVideos` graph — read-only video listing with pagination.

  NSID: com.etzhayyim.apps.yukkuri.listVideos
  Faithful clj port of `lg/lg_yukkuri/graphs/list_videos.py` (ADR-2606280030).

  Topology: START → query → audit → END.

  The DB read is the INJECTABLE `store/*select-where*` seam (kotoba-Datom-log
  target; RisingWave forbidden by the substrate boundary). limit clamps to
  1..200 (default 50), offset>=0; rows sort by created_at desc then page."
  (:require [langgraph.graph :as g]
            [lg-yukkuri.audit :as audit]
            [lg-yukkuri.store :as store]))

(defn- as-int [v d]
  (cond (integer? v) v
        (string? v) (try (Integer/parseInt v) (catch Exception _ d))
        :else d))

(defn- clamp [v lo hi] (max lo (min hi v)))

(defn- row->video [r]
  {:video_id   (or (:video_id r) (:video-id r))
   :owner_did  (or (:owner_did r) (:owner-did r))
   :topic      (:topic r)
   :status     (:status r)
   :render_url (or (:render_url r) (:render-url r))
   :created_at (or (:created_at r) (:created-at r))})

(defn node-query [state]
  (let [limit  (clamp (as-int (:limit state) 50) 1 200)
        offset (max 0 (as-int (:offset state) 0))
        owner  (:owner_did state)
        status (:status state)]
    (try
      (let [rows (cond
                   owner  (cond->> (store/select-where "vertex_yukkuri_video" "owner_did" owner 2000)
                            status (filter #(= (:status %) status)))
                   status (store/select-where "vertex_yukkuri_video" "status" status 2000)
                   :else  (->> (store/query "[:find (pull ?e [*]) :where [?e :vertex-yukkuri-video/video-id ?v]]")
                               (keep first)))
            sorted (sort-by #(str (or (:created_at %) (:created-at %) "")) #(compare %2 %1) rows)
            total  (count sorted)
            paged  (->> sorted (drop offset) (take limit))]
        {:videos (mapv row->video paged) :total total})
      (catch Exception e {:error (str "query: " (.getMessage e))}))))

(defn node-audit [state]
  (audit/emit-audit-bg {:actor (:app-did (audit/config-from-state state))
                        :activity "yukkuri.listVideos"
                        :object-id (str "listVideos:" (quot (System/currentTimeMillis) 1000))
                        :object-type "yukkuri.video"
                        :attributes {:returned (int (or (:total state) 0))}})
  {})

(defn build
  "Compile the listVideos StateGraph (query → audit)."
  []
  (-> (g/state-graph)
      (g/add-node :query node-query)
      (g/add-node :audit node-audit)
      (g/add-edge :query :audit)
      (g/set-entry-point :query)
      (g/set-finish-point :audit)
      (g/compile-graph)))

(def GRAPH (build))
