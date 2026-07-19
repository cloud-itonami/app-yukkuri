(ns lg-yukkuri.graphs.generate-bgm
  "yukkuri `generateBgm` graph — ongakuka.compose cross-project invoke.

  NSID: com.etzhayyim.apps.yukkuri.generateBgm
  Actor: did:web:yukkuri.etzhayyim.com:actor:composer
  Faithful clj port of `lg/lg_yukkuri/graphs/generate_bgm.py` (ADR-2606280030).

  Topology: START → fetch_topic → compose_bgm → insert_asset → audit → END.

  The cross-actor BGM call is the INJECTABLE `*compose-bgm*` boundary fn (posts
  to the ongakuka XRPC; default uses babashka.http-client). The CLAP cosine
  copyright guard (similarity > 0.92 → reject) is enforced server-side by
  ongakuka. Topic read + asset write go through the store seam."
  (:require #?(:clj [cheshire.core :as json])
            [clojure.string :as str]
            [langgraph.graph :as g]
            [lg-yukkuri.audit :as audit]
            [lg-yukkuri.store :as store]))

(defn- clip [s n] (let [s (str s)] (subs s 0 (min n (count s)))))
(defn- token-hex [n]
  (let [bs (byte-array n)] (.nextBytes (java.security.SecureRandom.) bs)
    (apply str (map #(format "%02x" %) bs))))

(defn compose-bgm-with
  "Default `*compose-bgm*`: POST to the ongakuka compose XRPC, return
  {:bgm_blob_key ...} | {:error ...}."
  ([http-post request] (compose-bgm-with http-post audit/graph-defaults request))
  ([http-post host-config {:keys [topic genre video-id]}]
  (when-not (fn? http-post)
    (throw (ex-info "BGM composition requires an explicit HTTP POST capability"
                    {:capability :yukkuri/ongakuka-http-post})))
  (try
    (let [r (http-post (:ongakuka-url (merge audit/graph-defaults host-config))
                       {:headers {"Content-Type" "application/json"} :throw false
                                :body (json/generate-string {:prompt (str "BGM for a Japanese educational video about: " topic)
                                                 :genre genre :durationSec 180 :loopable true
                                                 :projectId video-id})})]
      (if (>= (:status r) 400)
        {:error (str "ongakuka " (:status r) ": " (clip (:body r) 200))}
        (let [data (json/parse-string (:body r) true)
              bk   (or (:blobKey data) (:blob_key data) "")]
          (if (empty? bk) {:error "ongakuka returned no blobKey"} {:bgm_blob_key bk}))))
    (catch Exception e {:error (str "ongakuka: " (clip (.getMessage e) 180))}))))

(def ^:dynamic *compose-bgm* nil)

(defn node-fetch-topic [state]
  (if (:topic state)
    {}
    (let [video-id (or (:video_id state) "")]
      (if (= "" video-id)
        {}
        (try
          (let [rows (store/select-where "vertex_yukkuri_video" "video_id" video-id 1)]
            (if (seq rows) {:topic (or (:topic (first rows)) "")} {}))
          (catch Exception _ {}))))))

(defn node-compose-bgm [state]
  (if (:error state)
    {}
    (let [topic (str/trim (or (:topic state) "yukkuri educational video"))
          genre (or (:bgm_genre state) "calm_educational")
          _ (when-not (fn? *compose-bgm*)
              (throw (ex-info "generateBgm requires an explicit compose capability"
                              {:capability :yukkuri/compose-bgm})))
          res (*compose-bgm* {:topic topic :genre genre :video-id (or (:video_id state) "")})]
      res)))

(defn node-insert-asset [state]
  (if (or (:error state) (not (:bgm_blob_key state)))
    {}
    (let [composer-did (:composer-did (audit/config-from-state state))
          video-id (or (:video_id state) "")
          asset-id (str "asset-bgm-" video-id "-" (token-hex 3))
          created  (str (java.time.OffsetDateTime/now java.time.ZoneOffset/UTC))]
      (try
        (store/insert-row "vertex_yukkuri_asset"
                          {:vertex_id asset-id :video_id video-id :kind "bgm"
                           :actor_did composer-did :blob_key (:bgm_blob_key state)
                           :meta_json "{}" :created_at created})
        {:bgm_asset_id asset-id}
        (catch Exception e {:error (str "insert: " (clip (.getMessage e) 280))})))))

(defn node-audit [state]
  (audit/emit-audit-bg {:actor (:composer-did (audit/config-from-state state))
                        :activity "yukkuri.generateBgm"
                        :object-id (str "bgm:" (or (:video_id state) "") ":" (quot (System/currentTimeMillis) 1000))
                        :object-type "yukkuri.asset"
                        :attributes {:videoId (:video_id state) :ok (not (boolean (:error state)))}})
  {})

(defn build
  "Compile the generateBgm StateGraph."
  []
  (-> (g/state-graph)
      (g/add-node :fetch_topic node-fetch-topic)
      (g/add-node :compose_bgm node-compose-bgm)
      (g/add-node :insert_asset node-insert-asset)
      (g/add-node :audit node-audit)
      (g/add-edge :fetch_topic :compose_bgm)
      (g/add-edge :compose_bgm :insert_asset)
      (g/add-edge :insert_asset :audit)
      (g/set-entry-point :fetch_topic)
      (g/set-finish-point :audit)
      (g/compile-graph)))

(def GRAPH (build))
