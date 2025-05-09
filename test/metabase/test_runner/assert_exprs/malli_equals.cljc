(ns metabase.test-runner.assert-exprs.malli-equals
  (:require
   [clojure.test :as t]
   [malli.error :as me]
   [metabase.util :as u]
   [metabase.util.malli.registry :as mr])
  #?(:cljs
     (:require-macros [metabase.test-runner.assert-exprs.malli-equals])))

(defn malli=-report [message schema actuals]
  (doseq [actual actuals]
    (let [error (me/humanize (mr/explain schema actual))]
      (t/testing (if error
                   ;; Only serialize the value when there is an error to save time.
                   (str "\n" (u/pprint-to-str actual))
                   "\n")
        (t/do-report
         {:type     (if error :fail :pass)
          :message  message
          :expected schema
          :actual   actual
          :diffs    [[actual [error nil]]]})))))

#?(:clj
   (do
     ;; Clojure for Clojure usage
     (defmethod t/assert-expr 'malli=
       [message [_ schema & actuals]]
       `(malli=-report ~message ~schema ~(vec actuals)))

     ;; Clojure doing macroexpansion for ClojureScript usage.
     (when-let [assert-expr (try
                              (requiring-resolve 'cljs.test/assert-expr)
                              (catch Throwable _))]
       (defmethod (var-get assert-expr) 'malli=
         [_env message [_ schema & actuals]]
         `(malli=-report ~message ~schema ~(vec actuals))))))
