(ns lg-yukkuri.llm
  "Shared LLM seam for the yukkuri scriptwriter + critic graphs.

  DEVIATION (noted): the Python graphs post to a RunPod vLLM proxy URL
  (`VLLM_URL`). Per ADR-2605215000 (Murakumo DEFAULT-PREFERRED, loopback gateway
  127.0.0.1:4000, no-server-key / read-only) the inference edge here defaults to
  the Murakumo LiteLLM loopback and ASSERTS the endpoint is on the Murakumo
  fleet allowlist (ibuki guard pattern). The chat call itself is an INJECTABLE
  dynamic var so tests rebind it to a deterministic stub and verify offline."
  (:require #?(:clj [cheshire.core :as json])
            [clojure.string :as str]))

(defn- clip [s n] (let [s (str s)] (subs s 0 (min n (count s)))))

;; Murakumo fleet (ADR-2605215000) — the ONLY inference endpoints representable.
(def murakumo-allowed-hosts
  #{"127.0.0.1:4000" "localhost:4000"
    "192.168.1.70:8077" "192.168.1.70:11434"
    "127.0.0.1:11434" "localhost:11434"})

(def default-config
  {:llm-url "http://127.0.0.1:4000/v1"
   :llm-model "tier0-general"
   :llm-timeout-ms 90000})

(defn assert-murakumo
  "Refuse any LLM endpoint outside the Murakumo fleet (http loopback only)."
  [endpoint]
  (let [[_ scheme host] (or (re-find #"^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)" (str endpoint))
                            [nil nil nil])]
    (when-not (and (= "http" (some-> scheme str/lower-case))
                   (contains? murakumo-allowed-hosts (some-> host str/lower-case)))
      (throw (ex-info (str "inference endpoint " (pr-str endpoint)
                           " is outside the Murakumo fleet (ADR-2605215000)")
                      {:murakumo-only-violation true :endpoint endpoint})))))

(defn chat-json-with
  "Default `*chat-json*`: POST a chat-completions request (JSON response mode)
  to the Murakumo loopback gateway. Returns the raw assistant content string or
  {:error ...}. opts = {:max-tokens :temperature}."
  ([http-post system user opts]
   (chat-json-with http-post default-config system user opts))
  ([http-post host-config system user {:keys [max-tokens temperature]}]
   (when-not (fn? http-post)
     (throw (ex-info "Yukkuri inference requires an explicit HTTP POST capability"
                     {:capability :yukkuri/murakumo-http-post})))
   (let [{:keys [llm-url llm-model llm-timeout-ms]}
         (merge default-config (or host-config {}))
         llm-url (str/replace llm-url #"/+$" "")]
     (assert-murakumo llm-url)
     (try
       (let [resp (http-post (str llm-url "/chat/completions")
                             {:headers {"Content-Type" "application/json"}
                              :timeout (long llm-timeout-ms)
                              :throw false
                              :body (json/generate-string
                                     {:model llm-model
                                      :messages [{:role "system" :content system}
                                                 {:role "user" :content user}]
                                      :max_tokens (or max-tokens 1000)
                                      :temperature (or temperature 0.7)
                                      :response_format {:type "json_object"}})})
             status (:status resp)]
         (if (>= status 400)
           {:error (str "vllm " status ": " (clip (:body resp) 200))}
           (let [body (json/parse-string (:body resp) true)
                 txt (some-> (get-in body [:choices 0 :message :content]) str)]
             (or txt ""))))
       (catch Exception e
         {:error (clip (.getMessage e) 200)})))))

(def ^:dynamic *chat-json*
  "Injectable chat edge. (system user opts) → content string | {:error ...}."
  nil)

(defn chat-json [system user opts]
  (when-not (fn? *chat-json*)
    (throw (ex-info "Yukkuri inference requires an explicit chat capability"
                    {:capability :yukkuri/chat-json})))
  (*chat-json* system user opts))

(defn parse-json-object
  "Lenient JSON-object parse mirroring the Python fallback: try whole string,
  else extract the first {...} block. Returns a clj map (keyword keys) or nil."
  [raw]
  (let [try1  #?(:clj (try (json/parse-string (str raw) true) (catch Exception _ nil))
                  :default nil)]
    (if (map? try1)
      try1
      (let [s (str raw)
            i (str/index-of s "{")
            j (str/last-index-of s "}")]
        (when (and i j (< i j))
          #?(:clj (try (json/parse-string (subs s i (inc j)) true) (catch Exception _ nil))
             :default nil))))))
