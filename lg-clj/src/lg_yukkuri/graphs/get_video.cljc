(ns lg-yukkuri.graphs.get-video
  "yukkuri `getVideo` graph — video detail with scenes + lines + assets.

  NSID: com.etzhayyim.apps.yukkuri.getVideo
  Faithful clj port of `lg/lg_yukkuri/graphs/get_video.py` (ADR-2606280030).

  Topology: START → fetch_video → fetch_scenes → fetch_lines → fetch_assets
            → audit → END.

  All four fetches read through the INJECTABLE `store/*select-where*` seam.
  fetch_scenes/lines/assets short-circuit (return {}) when fetch_video errored
  or found nothing, exactly as the Python guards do."
  (:require [langgraph.graph :as g]
            [lg-yukkuri.audit :as audit]
            [lg-yukkuri.store :as store]))

(defn- as-int [v d] (cond (integer? v) v (string? v) (try (Integer/parseInt v) (catch Exception _ d)) :else d))

(defn node-fetch-video [state]
  (let [video-id (or (:video_id state) "")]
    (if (= "" video-id)
      {:error "video_id required"}
      (try
        (let [rows (store/select-where "vertex_yukkuri_video" "video_id" video-id 1)]
          (if (empty? rows)
            {:error (str "video not found: " video-id)}
            (let [r (first rows)]
              {:video {:videoId       (:video_id r)
                       :ownerDid      (:owner_did r)
                       :topic         (:topic r)
                       :outline       (:outline r)
                       :status        (:status r)
                       :renderUrl     (:render_url r)
                       :renderBlobKey (:render_blob_key r)
                       :createdAt     (str (or (:created_at r) ""))}})))
        (catch Exception e {:error (str "fetch: " (.getMessage e))})))))

(defn- skip? [state] (or (:error state) (not (:video state))))

(defn node-fetch-scenes [state]
  (if (skip? state)
    {}
    (try
      (let [rows (->> (store/select-where "vertex_yukkuri_scene" "video_id" (:video_id state) 100)
                      (sort-by #(as-int (:scene_index %) 0)))]
        {:scenes (mapv (fn [r] {:sceneIndex (as-int (:scene_index r) 0)
                                :location (:location r) :action (:action r)}) rows)})
      (catch Exception _ {:scenes []}))))

(defn node-fetch-lines [state]
  (if (skip? state)
    {}
    (try
      (let [rows (->> (store/select-where "vertex_yukkuri_line" "video_id" (:video_id state) 500)
                      (sort-by (juxt #(as-int (:scene_index %) 0) #(as-int (:line_index %) 0))))]
        {:lines (mapv (fn [r] {:sceneIndex (as-int (:scene_index r) 0)
                               :lineIndex (as-int (:line_index r) 0)
                               :speaker (:speaker r) :text (:text r)
                               :emotion (:emotion r) :voiceBlobKey (:voice_blob_key r)}) rows)})
      (catch Exception _ {:lines []}))))

(defn node-fetch-assets [state]
  (if (skip? state)
    {}
    (try
      (let [rows (->> (store/select-where "vertex_yukkuri_asset" "video_id" (:video_id state) 200)
                      (sort-by #(str (or (:created_at %) ""))))]
        {:assets (mapv (fn [r] {:kind (:kind r) :actorDid (:actor_did r)
                                :blobKey (:blob_key r) :createdAt (str (or (:created_at r) ""))}) rows)})
      (catch Exception _ {:assets []}))))

(defn node-audit [state]
  (audit/emit-audit-bg {:actor (:app-did (audit/config-from-state state))
                        :activity "yukkuri.getVideo"
                        :object-id (str "video:" (or (:video_id state) "") ":" (quot (System/currentTimeMillis) 1000))
                        :object-type "yukkuri.video"
                        :attributes {:videoId (:video_id state) :found (some? (:video state))}})
  {})

(defn build
  "Compile the getVideo StateGraph."
  []
  (-> (g/state-graph)
      (g/add-node :fetch_video node-fetch-video)
      (g/add-node :fetch_scenes node-fetch-scenes)
      (g/add-node :fetch_lines node-fetch-lines)
      (g/add-node :fetch_assets node-fetch-assets)
      (g/add-node :audit node-audit)
      (g/add-edge :fetch_video :fetch_scenes)
      (g/add-edge :fetch_scenes :fetch_lines)
      (g/add-edge :fetch_lines :fetch_assets)
      (g/add-edge :fetch_assets :audit)
      (g/set-entry-point :fetch_video)
      (g/set-finish-point :audit)
      (g/compile-graph)))

(def GRAPH (build))
