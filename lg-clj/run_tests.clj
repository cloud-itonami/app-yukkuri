(ns lg-yukkuri.host
  "Existing host-only test entrypoint and explicit HTTP capability adapter."
  (:require [babashka.http-client :as http]
            [clojure.test :as t]
            [lg-yukkuri.audit :as audit]
            [lg-yukkuri.llm :as llm]
            [lg-yukkuri.graphs.generate-bgm :as bgm]
            [lg-yukkuri.graphs.generate-visual :as visual]
            [lg-yukkuri.graphs.synthesize-voice :as voice]
            [lg-yukkuri.graphs.review-video :as review]
            [lg-yukkuri.graphs.render-video :as render]
            [lg-yukkuri.smoke-test]))

(defn with-capabilities
  ([f] (with-capabilities {} f))
  ([{:keys [llm audit] :as host-config} f]
  (binding [llm/*chat-json* (partial llm/chat-json-with http/post llm)
            audit/*emit* (partial audit/http-emit-with http/post audit)
            bgm/*compose-bgm* (partial bgm/compose-bgm-with http/post host-config)
            visual/*generate-one* (partial visual/generate-one-with http/post host-config)
            voice/*tts-one* (partial voice/tts-one-with http/post host-config)
            review/*social-publish* (partial review/social-publish-with http/post host-config)
            render/*render* (partial render/render-with http/post host-config)]
    (f))))

(defn run-tests! []
  (let [{:keys [fail error]} (t/run-tests 'lg-yukkuri.smoke-test)]
    (when (pos? (+ (or fail 0) (or error 0)))
      (System/exit 1))))

(run-tests!)
