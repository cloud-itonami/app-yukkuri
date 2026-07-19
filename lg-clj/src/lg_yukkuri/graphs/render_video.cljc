(ns lg-yukkuri.graphs.render-video
  "yukkuri `renderVideo` graph — timeline JSON → dougaka render pod → mp4.

  NSID: com.etzhayyim.apps.yukkuri.renderVideo
  Actor: did:web:yukkuri.etzhayyim.com:actor:renderer
  Faithful clj port of `lg/lg_yukkuri/graphs/render_video.py` (ADR-2606280030).

  Topology: START → build_timeline → render → update_status → audit → END.

  The render call is the INJECTABLE `*render*` boundary fn (posts to lg-dougaka
  XRPC; default uses babashka.http-client). Timeline assembly + status write go
  through the store seam.

  DEVIATION (noted): the Python build_timeline polls RisingWave up to 60 s for
  streaming-INSERT visibility. The kotoba Datom log is read-committed (no
  streaming lag), so this port reads once with no retry loop; the assembly logic
  + the \"no scenes → error\" guard are identical. No RetryPolicy in langgraph-clj."
  (:require #?(:clj [cheshire.core :as json])
            [langgraph.graph :as g]
            [lg-yukkuri.audit :as audit]
            [lg-yukkuri.store :as store]))

(defn- as-int [v d] (cond (integer? v) v (string? v) (try (Integer/parseInt v) (catch Exception _ d)) :else d))
(defn- clip [s n] (let [s (str s)] (subs s 0 (min n (count s)))))
(defn- json-gen [m] #?(:clj (json/generate-string m) :default (str m)))
(defn- json-parse [s] #?(:clj (json/parse-string s true) :default nil))

(defn render-with
  "Default `*render*`: POST the timeline to the dougaka render XRPC, return
  {:render_blob_key :render_url} | {:error ...}."
  ([http-post video-id timeline] (render-with http-post audit/graph-defaults video-id timeline))
  ([http-post host-config video-id timeline]
  (when-not (fn? http-post)
    (throw (ex-info "video rendering requires an explicit HTTP POST capability"
                    {:capability :yukkuri/dougaka-http-post})))
  (try
    (let [dougaka-url (clojure.string/replace (:dougaka-url (merge audit/graph-defaults host-config)) #"/+$" "")
          r (http-post (str dougaka-url "/xrpc/com.etzhayyim.apps.dougaka.render")
                  {:headers {"Content-Type" "application/json"} :throw false
                   :body (json-gen {:video_id video-id :timeline timeline})})]
      (if (>= (:status r) 400)
        {:error (str "dougaka render " (:status r) ": " (clip (:body r) 300))}
        (let [data (json-parse (:body r))
              bk   (or (:blob_key data) (:blobKey data) "")
              url  (or (:blob_url data) (:blobUrl data) (:url data) "")]
          (if (empty? bk) {:error (str "dougaka render returned no blobKey")}
              {:render_blob_key bk :render_url url}))))
    (catch Exception e {:error (str "dougaka render: " (clip (.getMessage e) 280))}))))

(def ^:dynamic *render* nil)

(defn node-build-timeline [state]
  (let [video-id (or (:video_id state) "")]
    (if (= "" video-id)
      {:error "video_id required"}
      (let [raw-scenes (->> (store/select-where "vertex_yukkuri_scene" "video_id" video-id 20)
                            (sort-by #(as-int (:scene_index %) 0)))]
        (if (empty? raw-scenes)
          {:error (str "build_timeline: no scenes visible for video_id=" video-id)}
          (let [scenes (mapv (fn [r] {:index (as-int (:scene_index r) 0)
                                      :location (:location r) :action (:action r)}) raw-scenes)
                lines  (->> (store/select-where "vertex_yukkuri_line" "video_id" video-id 500)
                            (sort-by (juxt #(as-int (:scene_index %) 0) #(as-int (:line_index %) 0)))
                            (mapv (fn [r] {:sceneIndex (as-int (:scene_index r) 0)
                                           :lineIndex (as-int (:line_index r) 0) :speaker (:speaker r)
                                           :text (:text r) :emotion (:emotion r)
                                           :voiceBlobKey (:voice_blob_key r)})))
                assets (mapv (fn [r] {:kind (:kind r) :blobKey (:blob_key r)
                                      :meta (try (json-parse (or (:meta_json r) "{}")) (catch Exception _ {}))})
                             (store/select-where "vertex_yukkuri_asset" "video_id" video-id 100))]
            {:timeline_json (json-gen {:videoId video-id :scenes scenes :lines lines :assets assets
                                       :format "mp4" :resolution "1280x720" :fps 30})}))))))

(defn node-render [state]
  (if (or (:error state) (not (:timeline_json state)))
    {}
    (do
      (when-not (fn? *render*)
        (throw (ex-info "renderVideo requires an explicit render capability"
                        {:capability :yukkuri/render})))
      (*render* (or (:video_id state) "") (json-parse (:timeline_json state))))))

(defn node-update-status [state]
  (if (or (:error state) (not (:render_blob_key state)))
    {}
    (try
      (let [rows (store/select-where "vertex_yukkuri_video" "video_id" (or (:video_id state) "") nil)]
        (when (seq rows)
          (store/insert-row "vertex_yukkuri_video"
                            (assoc (first rows) :status "rendered"
                                   :render_blob_key (:render_blob_key state)
                                   :render_url (:render_url state))))
        {})
      (catch Exception e {:error (str "update: " (clip (.getMessage e) 280))}))))

(defn node-audit [state]
  (audit/emit-audit-bg {:actor (:renderer-did (audit/config-from-state state))
                        :activity "yukkuri.renderVideo"
                        :object-id (str "render:" (or (:video_id state) "") ":" (quot (System/currentTimeMillis) 1000))
                        :object-type "yukkuri.render"
                        :attributes {:videoId (:video_id state) :blobKey (:render_blob_key state)
                                     :ok (not (boolean (:error state)))}})
  {})

(defn build
  "Compile the renderVideo StateGraph."
  []
  (-> (g/state-graph)
      (g/add-node :build_timeline node-build-timeline)
      (g/add-node :render node-render)
      (g/add-node :update_status node-update-status)
      (g/add-node :audit node-audit)
      (g/add-edge :build_timeline :render)
      (g/add-edge :render :update_status)
      (g/add-edge :update_status :audit)
      (g/set-entry-point :build_timeline)
      (g/set-finish-point :audit)
      (g/compile-graph)))

(def GRAPH (build))
