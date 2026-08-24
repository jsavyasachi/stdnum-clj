(ns stdnum.vies
  "Online EU VAT validation against the official VIES service
  (https://ec.europa.eu/taxation_customs/vies/). This namespace is the one part
  of the library that does network I/O. It stays separate, so that
  `stdnum.core` stays pure and keeps few dependencies. `check` confirms that a
  VAT number *exists* in the member-state registry, which a checksum cannot do.
  It also returns the trader name and address if the member state gives them.

      (require '[stdnum.vies :as vies])
      (vies/check \"DE136695976\")
      ;=> {:valid? true, :country \"DE\", :vat-number \"136695976\", :name \"...\", ...}

  Requires JDK 11+ (it uses java.net.http). On a network failure or a service
  failure, `check` returns `{:error <message>}` and does not throw."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.time Duration]))

(set! *warn-on-reflection* true)

(def ^:private default-endpoint
  "https://ec.europa.eu/taxation_customs/vies/rest-api/check-vat-number")

(def ^:private default-options
  {:endpoint default-endpoint
   :timeout-ms 20000
   :connect-timeout-ms 10000
   :max-retries 2
   :backoff-ms [100 250]})

(def ^:private transient-statuses #{408 425 429 500 502 503 504})
(def ^:private rate-limit-errors
  #{"GLOBAL_MAX_CONCURRENT_REQ"
    "MS_MAX_CONCURRENT_REQ"
    "REQUESTER_MS_MAX_CONCURRENT_REQ"})

(defn- split-vat [vat]
  (let [v (-> (str vat) (str/replace #"[\s.\-]" "") str/upper-case)]
    [(subs v 0 2) (subs v 2)]))

(defn parse-response
  "Pure: turn a VIES REST JSON response body into a result map. A successful reply
  gives `{:valid? :country :vat-number :name :address :request-date :raw}`. A
  member-state error (for example `MS_UNAVAILABLE` or `MS_MAX_CONCURRENT_REQ`)
  gives `{:error <code> :raw}`: the validity is unknown, not false. This
  function is public, so that you can test the parse without a network call."
  [^String body]
  (let [m (json/read-str body :key-fn keyword)
        errs (:errorWrappers m)]
    (if (or (seq errs) (false? (:actionSucceed m)))
      {:error (or (:error (first errs)) "VIES_ERROR") :raw m}
      {:valid?       (boolean (:valid m))
       :country      (:countryCode m)
       :vat-number   (:vatNumber m)
       :name         (:name m)
       :address      (:address m)
       :request-date (:requestDate m)
       :raw          m})))

(defn- default-transport
  [{:keys [^String url timeout-ms connect-timeout-ms body client]}]
  (let [http-client (or client
                        (.. (HttpClient/newBuilder)
                            (connectTimeout (Duration/ofMillis (long connect-timeout-ms)))
                            (build)))
        payload (json/write-str body)
        req (.. (HttpRequest/newBuilder (URI/create url))
                (timeout (Duration/ofMillis (long timeout-ms)))
                (header "Content-Type" "application/json")
                (header "Accept" "application/json")
                (POST (HttpRequest$BodyPublishers/ofString payload))
                (build))
        resp (.send ^HttpClient http-client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode ^HttpResponse resp)
     :headers (into {} (.map ^java.net.http.HttpHeaders (.headers ^HttpResponse resp)))
     :body (.body ^HttpResponse resp)}))

(defn- response-headers [response]
  (or (:headers response) {}))

(defn- rate-limit-metadata [headers]
  (when-let [retry-after (or (get headers "Retry-After")
                             (get headers "retry-after"))]
    {:retry-after retry-after :headers headers}))

(defn- enrich-response [result response]
  (let [headers (response-headers response)
        metadata (rate-limit-metadata headers)]
    (cond-> (merge result (select-keys response [:status]))
      (seq headers) (assoc :headers headers)
      metadata (assoc :rate-limit metadata))))

(defn- response-result [{:keys [status body] :as response}]
  (if (= 200 status)
    (enrich-response (parse-response body) response)
    (let [parsed (try (parse-response body) (catch Exception _ nil))]
      (if (and parsed (= "VIES_ERROR" (:error parsed)))
        (enrich-response {:error (str "VIES returned HTTP " status)} response)
        (enrich-response (or parsed {:error (str "VIES returned HTTP " status)})
                         response)))))

(defn- retryable-result? [result]
  (or (:transient? result)
      (contains? transient-statuses (:status result))
      (contains? rate-limit-errors (:error result))))

(defn- backoff-delay [backoff-ms retry-index]
  (cond
    (number? backoff-ms) (long backoff-ms)
    (seq backoff-ms) (long (or (nth (vec backoff-ms) retry-index nil)
                               (last backoff-ms)))
    :else 0))

(defn- do-check [country number options]
  (let [{:keys [endpoint timeout-ms connect-timeout-ms max-retries backoff-ms
                transport sleep-fn client]}
        (merge default-options options)
        transport (or transport default-transport)
        sleep-fn (or sleep-fn (fn [^long millis] (Thread/sleep millis)))
        request {:url endpoint
                 :timeout-ms timeout-ms
                 :connect-timeout-ms connect-timeout-ms
                 :client client
                 :body {:countryCode country :vatNumber number}}]
    (loop [retry-index 0]
      (let [result (try
                     (response-result (transport request))
                     (catch Exception e {:error (.getMessage e) :transient? true}))]
        (if (and (retryable-result? result) (< retry-index max-retries))
          (do (sleep-fn (backoff-delay backoff-ms retry-index))
              (recur (inc retry-index)))
          (dissoc result :transient?))))))

(defn check
  "Look up a VAT number against the live EU VIES service. Accepts a full VAT id
  with a country prefix (\"DE136695976\"), or an explicit `country` and `number`.
  Returns `{:valid? :country :vat-number :name :address :request-date :raw}` on a
  reply. Returns `{:error <message>}` on a network failure or a service failure.
  This function does a network request and requires JDK 11+. Options may provide
  `:endpoint`, `:timeout-ms`, `:connect-timeout-ms`, `:max-retries`,
  `:backoff-ms`, `:sleep-fn`, `:client`, or an injectable `:transport` function.
  The transport receives a request map and returns `{:status :headers :body}`."
  ([vat] (let [[c n] (split-vat vat)] (do-check c n {})))
  ([vat-or-country second]
   (if (map? second)
     (let [[c n] (split-vat vat-or-country)]
       (do-check c n second))
     (do-check vat-or-country second {})))
  ([country number options]
   (do-check country number options)))
