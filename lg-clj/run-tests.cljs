#!/usr/bin/env nbb
;; The suite on nbb -- no JVM, no build step.
;;
;;   nbb --classpath "src:test:$(clojure -Spath -M:test)" run-tests.cljs
;;
;; nbb does not read deps.edn, so the classpath has to be handed to it; the
;; `clojure -Spath` above is the only thing in this repo that still needs a
;; JVM, and only to resolve three git coordinates. On the fleet that is what
;; `:ship-git-deps true` supplies.
;;
;; WHY A SECOND RUNNER EXISTS. Every source file here is `.cljc` and until
;; 2026-09-01 exactly one runtime had ever loaded them: `bb test`, on a JVM.
;; The ClojureScript branch of every reader conditional was therefore
;; unexecuted, and two of them were wrong in the worst available way --
;; `llm/parse-json-object` and `render-video/json-parse` returned `nil` under
;; `:default`, which is the same value they return for "there was no JSON
;; here". A scriptwriter graph would have failed closed on every well-formed
;; model response and reported it as the model's fault. Nothing was red.
;;
;; So this runner is not a convenience. It is the half of the test surface
;; that was missing, and it is why `lg-yukkuri.compat` exists.
;; The exit code is three-valued, because "the suite failed" and "the suite
;; never ran" must not look alike from the outside:
;;
;;   0  every test ran and passed
;;   1  a test failed or errored
;;   2  REFUSED -- fewer tests ran than exist, so this run cannot report a pass
;;
;; `cljs.test/run-tests` does not return the summary the way `clojure.test`
;; does (it returns nil under nbb), so the count comes from the :end-run-tests
;; report hook. Reading it off the return value printed "Ran 40 tests" and
;; then refused with "nil tests ran" -- the floor caught its own plumbing,
;; which is the only reason it is written this way rather than the obvious way.
(ns run-tests
  (:require [clojure.test :as t]
            [lg-yukkuri.smoke-test]))

(def ^:private minimum-tests
  "Every `deftest` in lg-yukkuri.smoke-test. Raise this when tests are added:
  a runner naming a subset prints exactly the same `Ran N tests` line as one
  naming all of them."
  44)

(def ^:private summary (atom nil))

(defmethod t/report [:cljs.test/default :end-run-tests] [m] (reset! summary m))

(t/run-tests 'lg-yukkuri.smoke-test)

(let [{:keys [test fail error]} @summary]
  (cond
    (nil? @summary)
    (do (println "REFUSING to report a pass: the run produced no summary")
        (js/process.exit 2))

    (< (or test 0) minimum-tests)
    (do (println "REFUSING to report a pass:" test "tests ran, expected at least" minimum-tests)
        (js/process.exit 2))

    (pos? (+ (or fail 0) (or error 0)))
    (js/process.exit 1)))
