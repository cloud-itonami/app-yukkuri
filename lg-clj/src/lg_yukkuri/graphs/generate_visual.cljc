(ns lg-yukkuri.graphs.generate-visual
  "yukkuri `generateVisual` graph — 背景 + 挿絵 generation.

  NSID: com.etzhayyim.apps.yukkuri.generateVisual
  Actor: did:web:yukkuri.etzhayyim.com:actor:illustrator
  Faithful clj port of `lg/lg_yukkuri/graphs/generate_visual.py` (ADR-2606280030).

  Topology: START → fetch_scenes → generate → insert_assets → audit → END.

  The image-gen + uploadBlob is the INJECTABLE `*generate-one*` boundary fn
  (murakumo image endpoint + PDS uploadBlob); default uses babashka.http-client.
  Per-scene generation fans out via `pmap` (clj analogue of asyncio.gather).
  Copyright guardrail: a negative prompt is always attached (CLAUDE.md invariant).
  Node name `insert_assets` matches the Python `_build` wiring."
  (:require #?(:clj [cheshire.core :as json])
            [langgraph.graph :as g]
            [lg-yukkuri.audit :as audit]
            [lg-yukkuri.store :as store]))
(def negative-prompt "real person, celebrity, logo, watermark, nsfw, explicit")

(defn- as-int [v d] (cond (integer? v) v (string? v) (try (Integer/parseInt v) (catch Exception _ d)) :else d))
(defn- clip [s n] (let [s (str s)] (subs s 0 (min n (count s)))))

(defn- token-hex [n]
  (let [bs (byte-array n)] (.nextBytes (java.security.SecureRandom.) bs)
    (apply str (map #(format "%02x" %) bs))))

(defn generate-one-with
  "Default `*generate-one*`: image generation + uploadBlob for one scene."
  ([http-post scene] (generate-one-with http-post audit/graph-defaults scene))
  ([http-post host-config scene]
  (when-not (fn? http-post)
    (throw (ex-info "visual generation requires an explicit HTTP POST capability"
                    {:capability :yukkuri/image-http-post})))
  (try
    (let [{:keys [image-url pds-blob-url]} (merge audit/graph-defaults host-config)
          image-url (clojure.string/replace image-url #"/+$" "")
          b64dec   (java.util.Base64/getDecoder)
          prompt   (str "anime style background, " (:location scene) ", " (:action scene)
                        ", soft colors, 2D illustration")
          r (http-post image-url {:headers {"Content-Type" "application/json"} :throw false
                             :body (json/generate-string {:model "flux-schnell" :prompt prompt
                                              :negative_prompt negative-prompt
                                              :width 1280 :height 720 :num_inference_steps 4
                                              :response_format "b64_json"})})]
      (if (>= (:status r) 400)
        {:scene_index (:scene_index scene) :error (str "image " (:status r))}
        (let [b64 (get-in (json/parse-string (:body r) true) [:data 0 :b64_json] "")]
          (if (empty? b64)
            {:scene_index (:scene_index scene) :error "empty b64"}
            (let [img (.decode b64dec ^String b64)
                  ub  (http-post pds-blob-url {:headers {"Content-Type" "image/png"} :throw false :body img})]
              (if (>= (:status ub) 400)
                {:scene_index (:scene_index scene) :error (str "uploadBlob " (:status ub))}
                {:scene_index (:scene_index scene)
                 :blob_key (get-in (json/parse-string (:body ub) true) [:blob :ref :$link] "")}))))))
    (catch Exception e {:scene_index (:scene_index scene) :error (clip (.getMessage e) 200)})))
  )

(def ^:dynamic *generate-one* nil)

(defn- fetch-scenes [video-id]
  (->> (store/select-where "vertex_yukkuri_scene" "video_id" video-id 20)
       (sort-by #(as-int (:scene_index %) 0))
       (mapv (fn [r] {:scene_index (as-int (:scene_index r) 0)
                      :location (or (:location r) "") :action (or (:action r) "")}))))

(defn node-fetch-scenes [state]
  (let [video-id (or (:video_id state) "")]
    (if (= "" video-id)
      {:error "video_id required"}
      (try {:scenes (fetch-scenes video-id)}
           (catch Exception e {:error (str "fetch: " (clip (.getMessage e) 180))})))))

(defn node-generate [state]
  (if (:error state)
    {}
    (let [scenes (or (:scenes state) [])]
      (if (empty? scenes)
        {:visual_assets [] :generated_count 0}
        (do
          (when-not (fn? *generate-one*)
            (throw (ex-info "generateVisual requires an explicit generation capability"
                            {:capability :yukkuri/generate-one})))
          (let [ok (vec (remove :error (doall (pmap *generate-one* scenes))))]
            {:visual_assets ok :generated_count (count ok)}))))))

(defn node-insert-assets [state]
  (if (or (:error state) (empty? (:visual_assets state)))
    {}
    (let [illustrator-did (:illustrator-did (audit/config-from-state state))
          video-id (or (:video_id state) "")
          created  (str (java.time.OffsetDateTime/now java.time.ZoneOffset/UTC))]
      (try
        (doseq [asset (:visual_assets state)]
          (store/insert-row "vertex_yukkuri_asset"
                            {:vertex_id (str "asset-img-" video-id "-" (:scene_index asset) "-" (token-hex 3))
                             :video_id video-id :kind "image" :actor_did illustrator-did
                             :blob_key (:blob_key asset)
                             :meta_json (str "{\"sceneIndex\":" (:scene_index asset) "}")
                             :created_at created}))
        {}
        (catch Exception e {:error (str "insert: " (clip (.getMessage e) 280))})))))

(defn node-audit [state]
  (audit/emit-audit-bg {:actor (:illustrator-did (audit/config-from-state state))
                        :activity "yukkuri.generateVisual"
                        :object-id (str "visual:" (or (:video_id state) "") ":" (quot (System/currentTimeMillis) 1000))
                        :object-type "yukkuri.asset"
                        :attributes {:videoId (:video_id state) :count (or (:generated_count state) 0)}})
  {})

(defn build
  "Compile the generateVisual StateGraph."
  []
  (-> (g/state-graph)
      (g/add-node :fetch_scenes node-fetch-scenes)
      (g/add-node :generate node-generate)
      (g/add-node :insert_assets node-insert-assets)
      (g/add-node :audit node-audit)
      (g/add-edge :fetch_scenes :generate)
      (g/add-edge :generate :insert_assets)
      (g/add-edge :insert_assets :audit)
      (g/set-entry-point :fetch_scenes)
      (g/set-finish-point :audit)
      (g/compile-graph)))

(def GRAPH (build))
