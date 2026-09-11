(ns lg-yukkuri.smoke-test
  "Smoke tests for the lg-yukkuri clj port — clojure.test analogue of the Python
  `tests/test_smoke.py`, plus node-behaviour tests the original could not run
  offline (kotoba/LLM/TTS/image/render are injectable seams here, so the full
  pipeline topology + transforms verify under bb with stubs)."
  (:require [kotoba.lang.text] [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [lg-yukkuri.compat :as compat]
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

(deftest camel-to-snake-does-not-prefix-the-first-character
  ;; `_camel_to_snake` skips index 0, so a key that already starts upper case
  ;; lower-cases without gaining a leading underscore. Every case above starts
  ;; lower case, so all of them pass with the index guard deleted -- the key
  ;; would silently become :_video_id and every graph would read nil.
  (is (= "video_id" (server/camel->snake "VideoId")))
  (is (= "status" (server/camel->snake "Status")))
  (is (= {:video_id "v1"} (server/coerce-xrpc-input {"VideoId" "v1"}))))

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
      (is (kotoba.lang.text/starts-with? (:vertex_id @inserted)
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

(deftest render-video-timeline-round-trips-asset-meta
  ;; `node-build-timeline` reads each asset's `meta_json` column and folds it
  ;; into the timeline it hands the renderer. Both halves of that -- the parse
  ;; on the way in and the generate on the way out -- had a `:default` branch
  ;; that returned nil / EDN under ClojureScript until 2026-09-01. The happy
  ;; path above asserts on the RENDER RESULT, which the stub supplies, so it
  ;; stays green with `json-parse` replaced by `(constantly nil)`: the meta is
  ;; simply absent from a string nobody reads. This reads the string.
  (let [timelines (atom [])]
    (binding [store/*select-where*
              (fn [table _ _ _]
                (case table
                  "vertex_yukkuri_scene" [{:scene_index 0 :location "L" :action "A"}]
                  "vertex_yukkuri_line"  [{:scene_index 0 :line_index 0 :speaker "left" :text "x"}]
                  "vertex_yukkuri_asset" [{:kind "image" :blob_key "k"
                                           :meta_json "{\"sceneIndex\":7,\"width\":1280}"}]
                  "vertex_yukkuri_video" [{:video_id "v1" :status "assembled"}]
                  []))
              store/*insert-row* (fn [_t row] row)
              rv/*render* (fn [_vid timeline] (swap! timelines conj timeline)
                            {:render_blob_key "rk" :render_url "https://b2/x.mp4"})]
      (g/invoke rv/GRAPH {:video_id "v1"})
      ;; `node-build-timeline` GENERATES the JSON and `node-render` PARSES it
      ;; again before handing it over, so what the capability receives is a map
      ;; that has been through both halves of the codec. If either half is
      ;; wrong -- EDN out, or nil back -- this argument is nil.
      (let [tl (first @timelines)]
        (is (map? tl) "the renderer must receive a parsed timeline, not nil")
        (is (= "v1" (:videoId tl)))
        (is (= 7 (get-in tl [:assets 0 :meta :sceneIndex]))
            "asset meta_json must survive the round trip into the timeline")
        (is (= 1280 (get-in tl [:assets 0 :meta :width])))
        (is (= 1 (count (:scenes tl))))))))

(deftest list-videos-falls-back-to-the-declared-default-for-a-bad-limit
  ;; `as-int` exists so a non-numeric `limit` from the wire lands on the
  ;; documented default of 50 rather than throwing. Nothing asserted WHICH
  ;; value it lands on, so changing the fallback to any other number left the
  ;; suite green. 60 rows in, 50 out is the assertion that pins it.
  (binding [store/*select-where*
            (fn [_ _ _ _] (mapv (fn [i] {:video_id (str "v" i) :status "queued"
                                         :created_at (str "2026-09-01T00:00:" (when (< i 10) "0") i)})
                                (range 60)))]
    (let [out (g/invoke lv/GRAPH {:owner_did "d" :limit "not-a-number"})]
      (is (= 60 (:total out)))
      (is (= 50 (count (:videos out))) "a bad limit must fall back to 50, not to some other number"))
    (let [out (g/invoke lv/GRAPH {:owner_did "d" :limit "7"})]
      (is (= 7 (count (:videos out))) "a numeric-string limit must still be honoured"))))

(deftest generated-rkeys-are-unique-so-no-record-overwrites-another
  ;; `compose`, `generate_visual` and `generate_bgm` each mint an rkey from
  ;; `compat/random-hex`, and the store seam is an UPSERT keyed on it. A
  ;; generator that repeats therefore does not fail: every insert returns
  ;; normally, the graph reports success, and the previous record is gone.
  ;; Replacing the ClojureScript branch of `random-hex` with a constant left
  ;; the entire suite green -- measured 2026-09-01.
  (testing "the generator itself"
    (let [xs (repeatedly 16 #(compat/random-hex 6))]
      (is (every? #(re-matches #"[0-9a-f]{12}" %) xs)
          "6 bytes must render as exactly 12 lowercase hex characters")
      (is (= 16 (count (distinct xs))) "random-hex must not repeat")))
  (testing "and the video ids that come out of the compose graph"
    (let [rows (atom [])]
      (binding [store/*insert-row* (fn [_t row] (swap! rows conj row) row)]
        (dotimes [_ 8] (g/invoke compose/GRAPH {:topic "同じ話題"})))
      (let [ids (mapv :video_id @rows)]
        (is (= 8 (count ids)))
        (is (every? #(re-matches #"video-[0-9a-f]{12}" %) ids))
        (is (= 8 (count (distinct ids)))
            "eight composes of the same topic must not collide onto one rkey")))))

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

(defn- refusal-data
  "Call `f`, return the ex-data of the refusal it throws, or nil if it returned.

  `(is (thrown? ExceptionInfo ...))` would pass for a refusal thrown for ANY
  reason -- a typo in the function name under test throws too. Reading the
  ex-data lets each assertion below name the reason it is actually testing for
  (`:murakumo-only-violation`), so the test fails if the guard is replaced by
  a different one that happens to also throw."
  [f]
  (try (f) nil
       (catch #?(:clj Exception :cljs :default) e (or (ex-data e) {:no-ex-data true}))))

(deftest murakumo-guard
  (testing "off-fleet endpoint refused, for being off-fleet"
    (let [d (refusal-data #(llm/assert-murakumo "https://api.openai.com/v1"))]
      (is (true? (:murakumo-only-violation d)))
      (is (= "https://api.openai.com/v1" (:endpoint d)))))
  (testing "loopback gateway allowed"
    (is (nil? (llm/assert-murakumo "http://127.0.0.1:4000/v1"))))
  (testing "https loopback refused -- the allowlist is on http, not just the host"
    (is (true? (:murakumo-only-violation (refusal-data #(llm/assert-murakumo "https://127.0.0.1:4000/v1"))))))
  (testing "off-fleet host over http refused -- the HOST allowlist, not the scheme"
    ;; The two conditions in `assert-murakumo` are `and`-ed, so an https URL is
    ;; refused for its scheme no matter what the host allowlist says. Testing
    ;; only https://api.openai.com/v1 therefore passes with api.openai.com
    ;; ADDED to murakumo-allowed-hosts -- measured 2026-09-01, that mutation
    ;; left the whole suite green. This case is the one that pins the host set.
    (is (true? (:murakumo-only-violation (refusal-data #(llm/assert-murakumo "http://api.openai.com/v1")))))
    (is (true? (:murakumo-only-violation (refusal-data #(llm/assert-murakumo "http://10.0.0.5:4000/v1"))))))
  (testing "every host on the allowlist is reachable over http"
    (doseq [h llm/murakumo-allowed-hosts]
      (is (nil? (llm/assert-murakumo (str "http://" h "/v1"))) h)))
  (testing "malformed endpoint refused"
    (is (true? (:murakumo-only-violation (refusal-data #(llm/assert-murakumo "not-a-url")))))))

(deftest outward-capabilities-fail-closed
  (binding [llm/*chat-json* nil
            gbgm/*compose-bgm* nil
            gvis/*generate-one* nil
            sv/*tts-one* nil
            rev/*social-publish* nil
            rv/*render* nil]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs :default) #"explicit chat capability"
                          (llm/chat-json "s" "u" {})))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs :default) #"explicit compose capability"
                          (gbgm/node-compose-bgm {:topic "t"})))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs :default) #"explicit generation capability"
                          (gvis/node-generate {:scenes [{}]})))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs :default) #"explicit TTS capability"
                          (sv/node-synthesize {:lines [{}]})))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs :default) #"explicit social capability"
                          (rev/node-social-publish {:review_passed true})))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs :default) #"explicit render capability"
                          (rv/node-render {:video_id "v" :timeline_json "{}"})))))

;; ── audit shim: injectable + disabled ───────────────────────────────────────

(deftest audit-emit-injectable
  (let [events (atom [])]
    (binding [audit/*emit* (fn [p] (swap! events conj p))]
      (audit/emit-audit-bg {:actor "a" :activity "act" :object-id "o" :object-type "t"}))
    (is (= 1 (count @events)))
    (is (= "act" (:activity (first @events))))))
