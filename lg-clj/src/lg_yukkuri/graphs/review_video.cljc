(ns lg-yukkuri.graphs.review-video
  "yukkuri `reviewVideo` graph — critic QA (IP / 表現 / deepfake).

  NSID: com.etzhayyim.apps.yukkuri.reviewVideo
  Actor: did:web:yukkuri.etzhayyim.com:actor:critic
  Faithful clj port of `lg/lg_yukkuri/graphs/review_video.py` (ADR-2606280030).

  Topology: START → fetch_content → llm_review → update_status → social_publish
            → audit → END.

  The LLM verdict routes through `llm/*chat-json*` (Murakumo loopback default,
  fleet-allowlist guard). The reviewer FAILS CLOSED on any LLM error/parse
  failure so unavailable safety infrastructure cannot publish content. On PASS →
  status 'published' + a T1 social post via the INJECTABLE `*social-publish*`
  boundary fn; on REJECT → status 'rejected'. Content read + status write go
  through the store seam."
  (:require #?(:clj [cheshire.core :as json])
            [clojure.string :as str]
            [langgraph.graph :as g]
            [lg-yukkuri.audit :as audit]
            [lg-yukkuri.llm :as llm]
            [lg-yukkuri.store :as store]))

(def review-system
  (str "You are a content safety reviewer for a Japanese educational video platform.\n"
       "Review the provided script excerpt and classify it as PASS or REJECT.\n\n"
       "REJECT if:\n- Contains real person's full name, contact info, or private information\n"
       "- Contains defamatory statements about real individuals or organizations\n"
       "- Simulates a real celebrity/public figure's voice or likeness\n"
       "- Contains inappropriate content (violence, explicit material)\n"
       "- Reproduces copyrighted lyrics/text verbatim (>30 chars)\n\n"
       "Output JSON only: {\"verdict\": \"PASS\"|\"REJECT\", \"reason\": \"<brief reason or null>\"}\n"))

(defn- as-int [v d] (cond (integer? v) v (string? v) (try (Integer/parseInt v) (catch Exception _ d)) :else d))
(defn- clip [s n] (let [s (str s)] (subs s 0 (min n (count s)))))

(defn social-publish-with
  "Default `*social-publish*`: best-effort app.bsky.feed.post on publish."
  ([http-post request] (social-publish-with http-post audit/graph-defaults request))
  ([http-post host-config {:keys [topic]}]
  (when-not (fn? http-post)
    (throw (ex-info "social publication requires an explicit HTTP POST capability"
                    {:capability :yukkuri/social-http-post})))
  (try
    (let [{:keys [pds-xrpc-url app-did]} (merge audit/graph-defaults host-config)]
    (http-post (str pds-xrpc-url "/app.bsky.feed.post")
            {:headers {"Content-Type" "application/json"} :throw false
             :body (json/generate-string {:did app-did
                              :text (str "🎬 新作ゆっくり動画: " (clip topic 80) "\nyukkuri.etzhayyim.com")
                              :collection "app.bsky.feed.post"})})
    nil)
    (catch Exception _ nil))))

(def ^:dynamic *social-publish* nil)

(defn node-fetch-content [state]
  (let [video-id (or (:video_id state) "")]
    (if (= "" video-id)
      {:error "video_id required"}
      (try
        (let [vrows (store/select-where "vertex_yukkuri_video" "video_id" video-id 1)
              topic (when (seq vrows) (:topic (first vrows)))
              lines (->> (store/select-where "vertex_yukkuri_line" "video_id" video-id 100)
                         (sort-by (juxt #(as-int (:scene_index %) 0) #(as-int (:line_index %) 0)))
                         (take 40))
              excerpt (str/join "\n" (map #(str (str/upper-case (or (:speaker %) "")) ": " (:text %)) lines))]
          {:topic topic :script_excerpt excerpt})
        (catch Exception e {:error (str "fetch: " (clip (.getMessage e) 180))})))))

(defn node-llm-review [state]
  (if (:error state)
    {}
    (let [user (str "Topic: " (or (:topic state) "") "\n\nScript excerpt:\n"
                    (clip (or (:script_excerpt state) "") 2000))
          res  (llm/chat-json review-system user {:max-tokens 200 :temperature 0.0})]
      (cond
        (map? res) {:review_passed false :review_reason "llm_unavailable"}
        :else (let [parsed (llm/parse-json-object res)]
                (if (nil? parsed)
                  {:review_passed false :review_reason "parse_error"}
                  {:review_passed (= "PASS" (:verdict parsed "PASS"))
                   :review_reason (:reason parsed)}))))))

(defn node-update-status [state]
  (if (or (:error state) (nil? (:review_passed state)))
    {}
    (let [new-status (if (:review_passed state) "published" "rejected")]
      (try
        (let [rows (store/select-where "vertex_yukkuri_video" "video_id" (or (:video_id state) "") nil)]
          (when (seq rows)
            (store/insert-row "vertex_yukkuri_video" (assoc (first rows) :status new-status)))
          {})
        (catch Exception e {:error (str "update: " (clip (.getMessage e) 280))})))))

(defn node-social-publish [state]
  (if (or (:error state) (not (:review_passed state)))
    {}
    (do (when-not (fn? *social-publish*)
          (throw (ex-info "reviewVideo requires an explicit social capability"
                          {:capability :yukkuri/social-publish})))
        (*social-publish* {:topic (or (:topic state) "新作ゆっくり動画")
                           :video-id (or (:video_id state) "")})
        {})))

(defn node-audit [state]
  (audit/emit-audit-bg {:actor (:critic-did (audit/config-from-state state))
                        :activity "yukkuri.reviewVideo"
                        :object-id (str "review:" (or (:video_id state) "") ":" (quot (System/currentTimeMillis) 1000))
                        :object-type "yukkuri.review"
                        :attributes {:videoId (:video_id state) :passed (:review_passed state)
                                     :reason (:review_reason state)}})
  {})

(defn build
  "Compile the reviewVideo StateGraph."
  []
  (-> (g/state-graph)
      (g/add-node :fetch_content node-fetch-content)
      (g/add-node :llm_review node-llm-review)
      (g/add-node :update_status node-update-status)
      (g/add-node :social_publish node-social-publish)
      (g/add-node :audit node-audit)
      (g/add-edge :fetch_content :llm_review)
      (g/add-edge :llm_review :update_status)
      (g/add-edge :update_status :social_publish)
      (g/add-edge :social_publish :audit)
      (g/set-entry-point :fetch_content)
      (g/set-finish-point :audit)
      (g/compile-graph)))

(def GRAPH (build))
