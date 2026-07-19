(ns lg-yukkuri.graphs.generate-script
  "yukkuri `generateScript` graph — LLM scriptwriter (L/R 掛け合い + scene 分割).

  NSID: com.etzhayyim.apps.yukkuri.generateScript
  Actor: did:web:yukkuri.etzhayyim.com:actor:scriptwriter
  Faithful clj port of `lg/lg_yukkuri/graphs/generate_script.py` (ADR-2606280030).

  Topology: START → fetch_video → llm_script → insert → audit → END.

  DEVIATIONS (noted): (1) langgraph-clj has no RetryPolicy. (2) the LLM edge
  routes through `llm/*chat-json*` which defaults to the Murakumo loopback
  gateway with the fleet allowlist guard (ADR-2605215000) instead of the RunPod
  vLLM proxy. Topic fetch + scene/line inserts go through the injectable store
  seam (kotoba-Datom-log; RisingWave forbidden)."
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [lg-yukkuri.audit :as audit]
            [lg-yukkuri.llm :as llm]
            [lg-yukkuri.store :as store]))

(def system-prompt
  (str "You are a Japanese \"yukkuri\" video scriptwriter. Given a topic, write a two-character\n"
       "commentary script in the style of Japanese educational YouTube videos.\n"
       "Characters:\n"
       "  LEFT  = \"ゆきり\" (Reimu-like, knowledgeable, calm)\n"
       "  RIGHT = \"まりり\" (Marisa-like, energetic, curious)\n\n"
       "Output valid JSON only:\n"
       "{\"scenes\":[{\"location\":\"...\",\"action\":\"...\","
       "\"lines\":[{\"speaker\":\"left\"|\"right\",\"text\":\"...\","
       "\"emotion\":\"normal\"|\"happy\"|\"surprised\"|\"sad\"|\"angry\"}]}]}\n\n"
       "Rules:\n- 5–8 scenes for a 3–5 minute video\n- Each scene has 3–6 lines\n"
       "- Start with RIGHT asking a question, LEFT answering\n- End with both summarising\n"
       "- Keep vocabulary accessible (N3 level)\n- Include 1 surprising fact mid-video\n"
       "- No real person names, no PII\n"))

(defn- now-iso [] (str (java.time.OffsetDateTime/now java.time.ZoneOffset/UTC)))

(defn node-fetch-video [state]
  (let [video-id (or (:video_id state) "")]
    (cond
      (= "" video-id) {:error "video_id required"}
      (:topic state)  {}
      :else (try
              (let [rows (store/select-where "vertex_yukkuri_video" "video_id" video-id 1)]
                (if (seq rows)
                  {:topic (or (:topic (first rows)) "") :outline (:outline (first rows))}
                  {}))
              (catch Exception _ {})))))

(defn node-llm-script [state]
  (if (:error state)
    {}
    (let [topic (str/trim (or (:topic state) ""))]
      (if (str/blank? topic)
        {:error "topic is empty"}
        (let [hint (if (seq (str (:outline state)))
                     (str "\nOutline provided by user:\n" (:outline state)) "")
              user (str "Topic: " topic hint)
              res  (llm/chat-json system-prompt user {:max-tokens 3000 :temperature 0.8})]
          (cond
            (map? res) {:error (or (:error res) "vllm: unknown error")}
            :else (let [parsed (llm/parse-json-object res)
                        scenes (when parsed (:scenes parsed))]
                    (cond
                      (nil? parsed) {:error (str "json_parse: " (subs (str res) 0 (min 200 (count (str res)))))}
                      (empty? scenes) {:error "llm returned empty scenes"}
                      :else {:scenes (vec scenes) :scene_count (count scenes)}))))))))

(defn node-insert [state]
  (if (or (:error state) (empty? (:scenes state)))
    {}
    (let [repo-did (:repo-did (audit/config-from-state state))
          video-id (or (:video_id state) "")
          created  (now-iso)]
      (try
        (doseq [[i scene] (map-indexed vector (:scenes state))]
          (let [scene-id (str "scene-" video-id "-" i)]
            (store/insert-row "vertex_yukkuri_scene"
                              {:vertex_id (str "at://" repo-did "/com.etzhayyim.apps.yukkuri.scene/" scene-id)
                               :scene_id scene-id :video_id video-id :scene_index i
                               :location (or (:location scene) "") :action (or (:action scene) "")
                               :created_at created})
            (doseq [[j line] (map-indexed vector (or (:lines scene) []))]
              (let [line-id (str "line-" video-id "-" i "-" j)]
                (store/insert-row "vertex_yukkuri_line"
                                  {:vertex_id (str "at://" repo-did "/com.etzhayyim.apps.yukkuri.line/" line-id)
                                   :line_id line-id :video_id video-id :scene_index i :line_index j
                                   :speaker (or (:speaker line) "left") :text (or (:text line) "")
                                   :emotion (or (:emotion line) "normal") :created_at created})))))
        (let [vrows (store/select-where "vertex_yukkuri_video" "video_id" video-id nil)]
          (when (seq vrows)
            (store/insert-row "vertex_yukkuri_video" (assoc (first vrows) :status "script"))))
        {}
        (catch Exception e {:error (str "insert: " (.getMessage e))})))))

(defn node-audit [state]
  (audit/emit-audit-bg {:actor (:scriptwriter-did (audit/config-from-state state))
                        :activity "yukkuri.generateScript"
                        :object-id (str "script:" (or (:video_id state) "") ":" (quot (System/currentTimeMillis) 1000))
                        :object-type "yukkuri.script"
                        :attributes {:videoId (:video_id state) :sceneCount (:scene_count state)
                                     :ok (not (boolean (:error state)))}})
  {})

(defn build
  "Compile the generateScript StateGraph."
  []
  (-> (g/state-graph)
      (g/add-node :fetch_video node-fetch-video)
      (g/add-node :llm_script node-llm-script)
      (g/add-node :insert node-insert)
      (g/add-node :audit node-audit)
      (g/add-edge :fetch_video :llm_script)
      (g/add-edge :llm_script :insert)
      (g/add-edge :insert :audit)
      (g/set-entry-point :fetch_video)
      (g/set-finish-point :audit)
      (g/compile-graph)))

(def GRAPH (build))
