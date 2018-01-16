(ns request-flow.core
  (:require [clojure.tools.logging :as log]
            [ring.util.response :refer [response response? status not-found content-type]]
            [request-flow.http-status :as http-status]))

(defn- accept-header
  [ctx]
  (get-in ctx [:request :headers "accept"]))

(defn- media-types-available?
  ([ctx types]
   (boolean
     (when-let [accept (accept-header ctx)]
       (log/trace (str "accept:" (pr-str accept)))
       (some #(>= (.indexOf ^String accept ^String %) 0) (conj types "*/*"))))))

(defn check-media-types
  [& types]
  (fn [ctx]
    (if-not (media-types-available? ctx types)
      (do
        (log/warn (str "Unsupported Media type: " (accept-header ctx)))
        (-> (response "Unsupported Media types")
            (status http-status/NotAcceptable)))
      ctx)))

(declare flow)

(defmacro let-and-do
  [fns]
  (let [bindings (first fns)
        rest-fns (rest fns)]
    `(fn [prev-result#]
       (if (response? prev-result#)
         prev-result#
         (let [~'% prev-result#
               ~@bindings]
           ((flow ~@rest-fns) prev-result#))))))

(defn flow*
  [& fns]
  (fn [context]
    (loop [ctx context [f & remains] fns]
      (if f
        (let [result (f ctx)]
          (if (response? result)
            result
            (recur result remains)))
        ctx))))

(defmacro flow
  [& fns]
  (let [[fns-block [l & remains]] (split-with #(not= % :let) fns)]
    (if (seq remains)
      `(do
         (flow*
           ~@fns-block
           (let-and-do ~remains)))
      `(flow* ~@fns-block))))

(defn- validate
  [ctx validator params]
  (if-let [validation-result (not-empty (validator params))]
    {:next-context      nil
     :validation-result validation-result}
    {:next-context ctx
     :validation-result nil}))

(defn check-parameters-then
  ([validator params then-fn]
   (fn [{{req-params :params} :request, :as ctx}]
     (let [{:keys [next-context validation-result]} (validate ctx validator (merge req-params params))]
       (if validation-result
         (then-fn ctx validation-result)
         (do
           (assert (some? next-context) "Next Context Not Found")
           next-context)))))
  ([validator then-fn]
   (check-parameters-then validator nil then-fn)))

(defn check-exists
  [check-fn]
  (fn [ctx]
    (if-let [result (check-fn ctx)]
      (cond
        (associative? result)
        (merge ctx result)

        (string? result)
        (not-found result)

        :else
        ctx)
      (not-found "Not Found"))))

(defn run-handler
  [handler]
  (fn [req]
    (handler {:request req})))