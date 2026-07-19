(ns lg-yukkuri.audit
  "Fire-and-forget BPMN `generic.audit.emit` shim — clj port of
  `lg/lg_yukkuri/audit.py` (ADR-2606280030).

  The Python posts to the in-cluster bpmn-dispatcher via httpx; here the emit is
  an INJECTABLE dynamic var (`*emit*`) defaulting to a no-op, exactly mirroring
  the Python `LG_AUDIT_DISABLED` path. The default HTTP emitter
  (`http-emit`) uses babashka.http-client + cheshire and is wired only when a
  dispatcher URL is configured. Audit is best-effort: failures are swallowed
  (the Python wraps the post in try/except and logs a warning)."
  (:require #?(:clj [cheshire.core :as json])
            [clojure.string :as str]))

(def default-config
  {:dispatcher-url "http://bpmn-dispatcher.mitama-udf.svc.cluster.local:8080"
   :internal-secret ""
   :audit-timeout-ms 3000})

(def graph-defaults
  {:app-did "did:web:yukkuri.etzhayyim.com"
   :repo-did "did:web:y5kk5r1x.etzhayyim.com"
   :scriptwriter-did "did:web:yukkuri.etzhayyim.com:actor:scriptwriter"
   :illustrator-did "did:web:yukkuri.etzhayyim.com:actor:illustrator"
   :composer-did "did:web:yukkuri.etzhayyim.com:actor:composer"
   :renderer-did "did:web:yukkuri.etzhayyim.com:actor:renderer"
   :critic-did "did:web:yukkuri.etzhayyim.com:actor:critic"
   :image-url "http://127.0.0.1:4000/v1/images/generations"
   :tts-url "http://127.0.0.1:4000/v1/audio/speech"
   :pds-blob-url "https://atproto.etzhayyim.com/xrpc/com.atproto.repo.uploadBlob"
   :pds-xrpc-url "https://atproto.etzhayyim.com/xrpc"
   :voice-preset {"left" "af_heart" "right" "am_puck"}
   :ongakuka-url "https://atproto.etzhayyim.com/xrpc/com.etzhayyim.ongakuka.compose"
   :dougaka-url "http://lg-dougaka.mitama-udf.svc.cluster.local:8000"})

(defn config-from-state [state]
  (merge graph-defaults (or (:host-config state) {})))

(defn http-emit-with
  "Default real emitter: POST the ActivityStreams-ish payload to the
  bpmn-dispatcher `generic.audit.emit` XRPC. Best-effort; returns nil."
  ([http-post payload]
   (http-emit-with http-post default-config payload))
  ([http-post host-config payload]
   (when-not (fn? http-post)
     (throw (ex-info "Yukkuri audit requires an explicit HTTP POST capability"
                     {:capability :yukkuri/audit-http-post})))
   (let [{:keys [dispatcher-url internal-secret audit-timeout-ms]}
         (merge default-config (or host-config {}))
         dispatcher-url (str/replace dispatcher-url #"/+$" "")]
     (try
       (let [headers (cond-> {"Content-Type" "application/json"}
                       (seq internal-secret) (assoc "x-internal-trust" internal-secret))]
         (http-post (str dispatcher-url "/xrpc/com.etzhayyim.generic.audit.emit")
                    {:headers headers
                     :timeout (long audit-timeout-ms)
                     :body (json/generate-string payload)})
         nil)
       (catch Exception _ nil)))))

(def ^:dynamic *emit*
  "Injectable sink. Default = no-op unless an emitter is wired by deployment.
  Tests rebind this to capture audit events."
  (fn [_payload] nil))

(defn emit-audit-bg
  "Mirror of `audit.emit_audit_bg`: build the payload and hand it to `*emit*`
  fire-and-forget. Deployment chooses a no-op sink to disable audit. Always returns nil so audit
  nodes can `(emit-audit-bg ...)` then return {}."
  [{:keys [actor activity object-id object-type attributes]}]
  (*emit* {:actor      actor
           :activity   activity
           :objectId   object-id
           :objectType object-type
           :attributes (or attributes {})})
  nil)
