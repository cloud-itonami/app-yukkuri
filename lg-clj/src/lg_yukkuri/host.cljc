(ns lg-yukkuri.host
  "Wiring: bind every outward capability to one concrete HTTP POST.

  Each seam in this port defaults to a value that REFUSES rather than one that
  silently does nothing -- `llm/*chat-json*` is nil and `chat-json` throws,
  `store/*insert-row*` is an unconfigured no-op, `health/*rw-ping*` reports
  `rw_ok false`. That is deliberate: a graph reaching the network without
  anyone having granted it a way to do so should fail, not appear to succeed.
  This namespace is the single place a deployment grants that capability.

  It lived in `run_tests.clj` until 2026-09-01, which declared `ns
  lg-yukkuri.host` from a file named `run_tests.clj` -- a mismatch only
  `load-file` tolerated -- and imported `babashka.http-client` directly, so
  production wiring sat inside the test entry point and pinned both to a
  babashka runtime. `http-post` is now a parameter: callers pass
  `babashka.http-client/post`, `org.httpkit.client/post`, a Node `fetch`
  wrapper, or a capturing stub, and nothing here names a runtime."
  (:require [lg-yukkuri.audit :as audit]
            [lg-yukkuri.llm :as llm]
            [lg-yukkuri.graphs.generate-bgm :as bgm]
            [lg-yukkuri.graphs.generate-visual :as visual]
            [lg-yukkuri.graphs.synthesize-voice :as voice]
            [lg-yukkuri.graphs.review-video :as review]
            [lg-yukkuri.graphs.render-video :as render]))

(defn with-capabilities
  "Run `f` with every outward seam bound to `http-post`.

  `http-post` must be `(fn [url opts] -> {:status :body})`. `host-config` is
  the per-deployment map merged over `audit/graph-defaults` (endpoints, DIDs,
  voice presets); `:llm` and `:audit` are its two sub-configs.

  The store seams are NOT bound here. They are not HTTP, and binding them to
  the same capability would grant a graph the ability to write to the Datom
  log by virtue of being allowed to make an HTTP request."
  ([http-post f] (with-capabilities http-post {} f))
  ([http-post {:keys [llm audit] :as host-config} f]
   (when-not (fn? http-post)
     (throw (ex-info "lg-yukkuri host wiring requires an explicit HTTP POST capability"
                     {:capability :yukkuri/http-post})))
   (binding [llm/*chat-json*        (partial llm/chat-json-with http-post llm)
             audit/*emit*           (partial audit/http-emit-with http-post audit)
             bgm/*compose-bgm*      (partial bgm/compose-bgm-with http-post host-config)
             visual/*generate-one*  (partial visual/generate-one-with http-post host-config)
             voice/*tts-one*        (partial voice/tts-one-with http-post host-config)
             review/*social-publish* (partial review/social-publish-with http-post host-config)
             render/*render*        (partial render/render-with http-post host-config)]
     (f))))
