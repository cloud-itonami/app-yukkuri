(ns lg-yukkuri.smoke-test
  "Smoke tests for the lg-yukkuri clj port — clojure.test analogue of the Python
  `tests/test_smoke.py`, plus node-behaviour tests the original could not run
  offline (kotoba/LLM/TTS/image/render are injectable seams here, so the full
  pipeline topology + transforms verify under bb with stubs)."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [lg-yukkuri.server :as server]
            [lg-yukkuri.store :as store]
            [lg-yukkuri.audit :as audit]
            [lg-yukkuri.llm :as llm]
            [lg-yukkuri.graphs.health :as health]
            [lg-yukkuri.graphs.list-videos :as lv]
            [lg-yukkuri.graphs.get-video :as gv]
            [lg-yukkuri.graphs.compose :as compose]
            [lg-yukkuri.graphs.generate-script :as gs]
            [lg-yukkuri.graphs.synthesize-voice :as sv]
            [lg-yukkuri.graphs.generate-visual :as gvis]
            [lg-yukkuri.graphs.generate-bgm :as gbgm]
            [lg-yukkuri.graphs.render-video :as rv]
            [lg-yukkuri.graphs.review-video :as rev]))

(def expected-graphs
  #{"health" "list_videos" "get_video" "compose" "generate_script"
    "synthesize_voice" "generate_visual" "generate_bgm" "render_video" "review_video"})

(def expected-nsid-map
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

;; ── registry parity (mirrors test_smoke.py) ─────────────────────────────────

(deftest graphs-match-expected-set
  (is (= expected-graphs (set (keys server/GRAPHS)))))

(deftest nsid-map-completeness
  (is (= expected-nsid-map server/NSID-MAP))
  (is (= 10 (count server/NSID-MAP))))

(deftest nsid-map-references-known-graphs
  (doseq [[nsid gname] server/NSID-MAP]
    (is (contains? server/GRAPHS gname) (str nsid " → " gname " not in GRAPHS"))))

(deftest all-graphs-compiled
  (doseq [[nm graph] server/GRAPHS]
    (is (some? graph) (str "GRAPHS[" nm "] nil"))))

;; ── dispatch surface (/ok, /health, /runs, /xrpc) ───────────────────────────

(deftest ok-endpoint
  (let [r (server/ok)]
    (is (= 200 (:status r)))
    (is (true? (get-in r [:body :ok])))
    (is (= expected-graphs (set (get-in r [:body :graphs]))))
    (is (= "0.1.0" (get-in r [:body :version])))))

(deftest health-endpoint
  (is (= 200 (:status (server/health))))
  (is (true? (get-in (server/health) [:body :ok]))))

(deftest unknown-assistant-404
  (is (= 404 (:status (server/dispatch-run {:assistant_id "nope" :input {}})))))

(deftest unknown-nsid-404
  (is (= 404 (:status (server/dispatch-xrpc "com.etzhayyim.apps.yukkuri.unknownMethod" {})))))

(deftest api-key-guard
  (testing "no key configured → pass"
    (is (nil? (server/check-api-key ""))))
  (testing "configured key mismatch → 401"
    (is (= 401 (:status (server/dispatch-run {:assistant_id "health"}
                                             {:x-api-key "wrong" :api-key "secret123"}))))
    (is (= 200 (:status (server/dispatch-run {:assistant_id "health"}
                                             {:x-api-key "secret123" :api-key "secret123"}))))))

(deftest llm-host-config-is-explicit-and-allowlisted
  (let [request (atom nil)
        post (fn [url opts]
               (reset! request [url opts])
               {:status 200 :body "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}"})]
    (is (= "{}" (llm/chat-json-with post {:llm-url "http://localhost:4000/v1/"
                                           :llm-model "safe-model"
                                           :llm-timeout-ms 1234}
                                      "system" "user" {})))
    (is (= "http://localhost:4000/v1/chat/completions" (first @request)))
    (is (= 1234 (get-in @request [1 :timeout])))))

(deftest audit-secret-is-an-explicit-capability
  (let [request (atom nil)]
    (audit/http-emit-with (fn [url opts] (reset! request [url opts]))
                          {:dispatcher-url "http://dispatcher.internal/"
                           :internal-secret "bound-secret"
                           :audit-timeout-ms 777}
                          {:activity "test"})
    (is (= "http://dispatcher.internal/xrpc/com.etzhayyim.generic.audit.emit"
           (first @request)))
    (is (= "bound-secret" (get-in @request [1 :headers "x-internal-trust"])))
    (is (= 777 (get-in @request [1 :timeout])))))

(deftest camel-to-snake-coercion
  (is (= "video_id" (server/camel->snake "videoId")))
  (is (= "generate_script" (server/camel->snake "generateScript")))
  (is (= {:video_id "v1" :owner_did "d"} (server/coerce-xrpc-input {"videoId" "v1" "ownerDid" "d"}))))

(deftest xrpc-dispatch-snake-coercion
  ;; getVideo with no store rows → error 'video not found' proves video_id flowed through
  (let [r (server/dispatch-xrpc "com.etzhayyim.apps.yukkuri.getVideo" {"videoId" "missing"})]
    (is (= 200 (:status r)))
    (is (re-find #"video not found" (str (get-in r [:body :error]))))))

;; ── health graph ────────────────────────────────────────────────────────────

(deftest health-graph-default-unconfigured
  (let [out (g/invoke health/GRAPH {})]
    (is (false? (:ok out)))
    (is (false? (:rw_ok out)))
    (is (string? (:server_now out)))))

(deftest health-graph-rw-ok-stubbed
  (binding [health/*rw-ping* (fn [] {:rw_ok true :rw_latency_ms 3})]
    (let [out (g/invoke health/GRAPH {})]
      (is (true? (:ok out)))
      (is (true? (:rw_ok out))))))

;; ── list_videos graph: clamp + row mapping ──────────────────────────────────

(deftest list-videos-clamps-and-maps
  (binding [store/*select-where*
            (fn [table col val limit]
              (is (= "vertex_yukkuri_video" table))
              (is (= "status" col))
              (is (= "queued" val))
              (is (= 2000 limit))
              [{:video_id "v1" :owner_did "o" :topic "t" :status "queued"
                :render_url nil :created_at "2026-06-01"}
               {:video_id "v2" :owner_did "o" :topic "t2" :status "queued"
                :render_url nil :created_at "2026-06-02"}])]
    (let [out (g/invoke lv/GRAPH {:status "queued" :limit 9999 :offset 0})]
      (is (= 2 (:total out)))
      ;; created_at desc → v2 first
      (is (= "v2" (-> out :videos first :video_id))))))

;; ── get_video graph: not-found + full detail via stub ───────────────────────

(deftest get-video-missing-id
  (is (= "video_id required" (:error (g/invoke gv/GRAPH {})))))

(deftest get-video-not-found
  (binding [store/*select-where* (fn [_ _ _ _] [])]
    (is (re-find #"not found" (:error (g/invoke gv/GRAPH {:video_id "x"}))))))

(deftest get-video-detail-stubbed
  (binding [store/*select-where*
            (fn [table _col _val _limit]
              (case table
                "vertex_yukkuri_video" [{:video_id "v1" :owner_did "o" :topic "T" :status "rendered"}]
                "vertex_yukkuri_scene" [{:scene_index 1 :location "L1" :action "A1"}
                                        {:scene_index 0 :location "L0" :action "A0"}]
                "vertex_yukkuri_line"  [{:scene_index 0 :line_index 0 :speaker "right" :text "Q"}]
                "vertex_yukkuri_asset" [{:kind "image" :actor_did "ill" :blob_key "k" :created_at "2026"}]
                []))]
    (let [out (g/invoke gv/GRAPH {:video_id "v1"})]
      (is (= "T" (get-in out [:video :topic])))
      (is (= [0 1] (mapv :sceneIndex (:scenes out))) "scenes sorted by index")
      (is (= 1 (count (:lines out))))
      (is (= "image" (-> out :assets first :kind))))))

;; ── compose graph: validation + insert via stub ─────────────────────────────

(deftest compose-requires-topic
  (is (= "topic is required" (:error (g/invoke compose/GRAPH {:topic "  "})))))

(deftest compose-topic-too-long
  (is (re-find #"too long" (:error (g/invoke compose/GRAPH {:topic (apply str (repeat 501 "x"))})))))

(deftest compose-happy-path-stubbed
  (let [inserted (atom nil)]
    (binding [store/*insert-row* (fn [table row] (reset! inserted [table row]) row)]
      (let [out (g/invoke compose/GRAPH {:topic "量子力学入門" :owner_did "did:web:u"})]
        (is (re-find #"^video-" (:video_id out)))
        (is (re-find #"com.etzhayyim.apps.yukkuri.video" (:video_uri out)))
        (is (= "vertex_yukkuri_video" (first @inserted)))
        (is (= "queued" (:status (second @inserted))))))))

(deftest graph-host-config-propagates-as-data
  (let [inserted (atom nil)]
    (binding [store/*insert-row* (fn [_ row] (reset! inserted row) row)]
      (compose/node-insert {:topic "safe"
                            :host-config {:app-did "did:web:explicit.example"
                                          :repo-did "did:web:repo.example"}})
      (is (= "did:web:explicit.example" (:owner_did @inserted)))
      (is (= "did:web:repo.example" (:repo @inserted)))
      (is (clojure.string/starts-with? (:vertex_id @inserted)
                                       "at://did:web:repo.example/")))))

;; ── generate_script graph: LLM stub → scenes → insert ───────────────────────

(deftest generate-script-empty-topic
  (binding [store/*select-where* (fn [_ _ _ _] [])]
    (is (= "topic is empty" (:error (g/invoke gs/GRAPH {:video_id "v1"}))))))

(deftest generate-script-happy-path-stubbed
  (let [rows (atom [])]
    (binding [llm/*chat-json* (fn [_sys _user _opts]
                                "{\"scenes\":[{\"location\":\"L\",\"action\":\"A\",\"lines\":[{\"speaker\":\"right\",\"text\":\"Q\",\"emotion\":\"happy\"}]}]}")
              store/*select-where* (fn [_ _ _ _] [{:video_id "v1" :status "queued"}])
              store/*insert-row* (fn [table row] (swap! rows conj [table row]) row)]
      (let [out (g/invoke gs/GRAPH {:video_id "v1" :topic "相対性理論"})]
        (is (= 1 (:scene_count out)))
        (is (= 1 (count (:scenes out))))
        (is (some #(= "vertex_yukkuri_scene" (first %)) @rows))
        (is (some #(= "vertex_yukkuri_line" (first %)) @rows))
        (is (some #(and (= "vertex_yukkuri_video" (first %)) (= "script" (:status (second %)))) @rows))))))

(deftest generate-script-llm-error
  (binding [llm/*chat-json* (fn [_ _ _] {:error "vllm 500: boom"})]
    (is (re-find #"boom" (:error (g/invoke gs/GRAPH {:video_id "v1" :topic "t"}))))))

;; ── synthesize_voice graph: parallel TTS via stub ───────────────────────────

(deftest synthesize-voice-missing-id
  (is (= "video_id required" (:error (g/invoke sv/GRAPH {})))))

(deftest synthesize-voice-happy-path-stubbed
  (let [updated (atom [])]
    (binding [store/*select-where*
              (fn [table _col _val _limit]
                (if (= table "vertex_yukkuri_line")
                  [{:line_id "l0" :scene_index 0 :line_index 0 :speaker "left" :text "あ"}
                   {:line_id "l1" :scene_index 0 :line_index 1 :speaker "right" :text "い"}]
                  []))
              store/*insert-row* (fn [_t row] (swap! updated conj row) row)
              sv/*tts-one* (fn [line] {:line_id (:line_id line) :speaker (:speaker line)
                                       :blob_key (str "blob-" (:line_id line))})]
      (let [out (g/invoke sv/GRAPH {:video_id "v1"})]
        (is (= 2 (:synthesized_count out)))
        (is (= 2 (count (:voice_assets out))))
        (is (= 2 (count @updated)))
        (is (every? :voice_blob_key @updated))))))

;; ── generate_visual graph: per-scene image via stub ─────────────────────────

(deftest generate-visual-happy-path-stubbed
  (let [assets (atom [])]
    (binding [store/*select-where*
              (fn [table _ _ _]
                (if (= table "vertex_yukkuri_scene")
                  [{:scene_index 0 :location "L0" :action "A0"}
                   {:scene_index 1 :location "L1" :action "A1"}]
                  []))
              store/*insert-row* (fn [_t row] (swap! assets conj row) row)
              gvis/*generate-one* (fn [s] {:scene_index (:scene_index s)
                                           :blob_key (str "img-" (:scene_index s))})]
      (let [out (g/invoke gvis/GRAPH {:video_id "v1"})]
        (is (= 2 (:generated_count out)))
        (is (= 2 (count @assets)))
        (is (every? #(= "image" (:kind %)) @assets))))))

(deftest generate-visual-skips-failed-scenes
  (binding [store/*select-where* (fn [t _ _ _] (if (= t "vertex_yukkuri_scene")
                                                 [{:scene_index 0 :location "L" :action "A"}] []))
            gvis/*generate-one* (fn [_s] {:scene_index 0 :error "image 500"})]
    (let [out (g/invoke gvis/GRAPH {:video_id "v1"})]
      (is (= 0 (:generated_count out)))
      (is (= [] (:visual_assets out))))))

;; ── generate_bgm graph: ongakuka stub ───────────────────────────────────────

(deftest generate-bgm-happy-path-stubbed
  (binding [store/*select-where* (fn [_ _ _ _] [{:topic "宇宙"}])
            store/*insert-row* (fn [_t row] row)
            gbgm/*compose-bgm* (fn [_args] {:bgm_blob_key "bgm-key"})]
    (let [out (g/invoke gbgm/GRAPH {:video_id "v1"})]
      (is (= "bgm-key" (:bgm_blob_key out)))
      (is (re-find #"^asset-bgm-" (:bgm_asset_id out))))))

(deftest generate-bgm-error
  (binding [gbgm/*compose-bgm* (fn [_] {:error "ongakuka 503: nope"})]
    (is (re-find #"nope" (:error (g/invoke gbgm/GRAPH {:video_id "v1" :topic "t"}))))))

;; ── render_video graph: timeline assembly + render stub ─────────────────────

(deftest render-video-no-scenes-error
  (binding [store/*select-where* (fn [_ _ _ _] [])]
    (is (re-find #"no scenes" (:error (g/invoke rv/GRAPH {:video_id "v1"}))))))

(deftest render-video-happy-path-stubbed
  (let [statuses (atom [])]
    (binding [store/*select-where*
              (fn [table _ _ _]
                (case table
                  "vertex_yukkuri_scene" [{:scene_index 0 :location "L" :action "A"}]
                  "vertex_yukkuri_line"  [{:scene_index 0 :line_index 0 :speaker "left" :text "x"}]
                  "vertex_yukkuri_asset" [{:kind "image" :blob_key "k" :meta_json "{\"sceneIndex\":0}"}]
                  "vertex_yukkuri_video" [{:video_id "v1" :status "assembled"}]
                  []))
              store/*insert-row* (fn [_t row] (swap! statuses conj (:status row)) row)
              rv/*render* (fn [_vid _timeline] {:render_blob_key "rk" :render_url "https://b2/x.mp4"})]
      (let [out (g/invoke rv/GRAPH {:video_id "v1"})]
        (is (= "rk" (:render_blob_key out)))
        (is (= "https://b2/x.mp4" (:render_url out)))
        (is (some #{"rendered"} @statuses))))))

;; ── review_video graph: fail-closed + verdict + publish ─────────────────────

(deftest review-video-pass-publishes
  (let [published (atom false) statuses (atom [])]
    (binding [store/*select-where*
              (fn [table _ _ _]
                (case table
                  "vertex_yukkuri_video" [{:video_id "v1" :topic "T" :status "rendered"}]
                  "vertex_yukkuri_line"  [{:scene_index 0 :line_index 0 :speaker "left" :text "安全な内容"}]
                  []))
              store/*insert-row* (fn [_t row] (swap! statuses conj (:status row)) row)
              llm/*chat-json* (fn [_ _ _] "{\"verdict\":\"PASS\",\"reason\":null}")
              rev/*social-publish* (fn [_args] (reset! published true) nil)]
      (let [out (g/invoke rev/GRAPH {:video_id "v1"})]
        (is (true? (:review_passed out)))
        (is (some #{"published"} @statuses))
        (is (true? @published))))))

(deftest review-video-reject-no-publish
  (let [published (atom false) statuses (atom [])]
    (binding [store/*select-where* (fn [t _ _ _] (if (= t "vertex_yukkuri_video")
                                                   [{:video_id "v1" :topic "T"}]
                                                   [{:scene_index 0 :line_index 0 :speaker "l" :text "x"}]))
              store/*insert-row* (fn [_t row] (swap! statuses conj (:status row)) row)
              llm/*chat-json* (fn [_ _ _] "{\"verdict\":\"REJECT\",\"reason\":\"real name\"}")
              rev/*social-publish* (fn [_args] (reset! published true) nil)]
      (let [out (g/invoke rev/GRAPH {:video_id "v1"})]
        (is (false? (:review_passed out)))
        (is (= "real name" (:review_reason out)))
        (is (some #{"rejected"} @statuses))
        (is (false? @published))))))

(deftest review-video-fails-closed-on-llm-error
  (binding [store/*select-where* (fn [t _ _ _] (if (= t "vertex_yukkuri_video") [{:topic "T"}] []))
            store/*insert-row* (fn [_t row] row)
            llm/*chat-json* (fn [_ _ _] {:error "vllm 500"})
            rev/*social-publish* (fn [_] nil)]
    (let [out (g/invoke rev/GRAPH {:video_id "v1"})]
      (is (false? (:review_passed out)) "safety review outage must block publication")
      (is (= "llm_unavailable" (:review_reason out))))))

;; ── Murakumo fleet guard (ADR-2605215000) ───────────────────────────────────

(deftest murakumo-guard
  (testing "off-fleet endpoint refused"
    (is (thrown? clojure.lang.ExceptionInfo (llm/assert-murakumo "https://api.openai.com/v1"))))
  (testing "loopback gateway allowed"
    (is (nil? (llm/assert-murakumo "http://127.0.0.1:4000/v1"))))
  (testing "malformed endpoint refused"
    (is (thrown? clojure.lang.ExceptionInfo (llm/assert-murakumo "not-a-url")))))

(deftest outward-capabilities-fail-closed
  (binding [llm/*chat-json* nil
            gbgm/*compose-bgm* nil
            gvis/*generate-one* nil
            sv/*tts-one* nil
            rev/*social-publish* nil
            rv/*render* nil]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit chat capability"
                          (llm/chat-json "s" "u" {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit compose capability"
                          (gbgm/node-compose-bgm {:topic "t"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit generation capability"
                          (gvis/node-generate {:scenes [{}]})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit TTS capability"
                          (sv/node-synthesize {:lines [{}]})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit social capability"
                          (rev/node-social-publish {:review_passed true})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit render capability"
                          (rv/node-render {:video_id "v" :timeline_json "{}"})))))

;; ── audit shim: injectable + disabled ───────────────────────────────────────

(deftest audit-emit-injectable
  (let [events (atom [])]
    (binding [audit/*emit* (fn [p] (swap! events conj p))]
      (audit/emit-audit-bg {:actor "a" :activity "act" :object-id "o" :object-type "t"}))
    (is (= 1 (count @events)))
    (is (= "act" (:activity (first @events))))))
