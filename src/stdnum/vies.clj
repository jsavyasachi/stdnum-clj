(ns stdnum.vies
  "Online EU VAT validation against the official VIES service
  (https://ec.europa.eu/taxation_customs/vies/). This is the one capability in
  the library that performs network I/O - it is kept in its own namespace so
  `stdnum.core` stays pure and dependency-light. `check` confirms a VAT number
  actually *exists* in the member-state registry (and returns the trader name /
  address where the state discloses them), which a checksum cannot do.

      (require '[stdnum.vies :as vies])
      (vies/check \"DE136695976\")
      ;=> {:valid? true, :country \"DE\", :vat-number \"136695976\", :name \"...\", ...}

  Requires JDK 11+ (uses java.net.http). On any network/service failure `check`
  returns `{:error <message>}` rather than throwing."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private endpoint
  "https://ec.europa.eu/taxation_customs/vies/rest-api/check-vat-number")

(defn- split-vat [vat]
  (let [v (-> (str vat) (str/replace #"[\s.\-]" "") str/upper-case)]
    [(subs v 0 2) (subs v 2)]))

(defn parse-response
  "Pure: turn a VIES REST JSON response body into a result map. A successful reply
  yields `{:valid? :country :vat-number :name :address :request-date :raw}`; a
  member-state error (e.g. `MS_UNAVAILABLE`, `MS_MAX_CONCURRENT_REQ`) yields
  `{:error <code> :raw}` - the validity is genuinely unknown, not false. Exposed
  so the parsing can be tested without a network call."
  [^String body]
  (let [m (json/read-str body :key-fn keyword)]
    (if-let [errs (and (or (:errorWrappers m) (false? (:actionSucceed m)))
                       (:errorWrappers m))]
      {:error (or (:error (first errs)) "VIES_ERROR") :raw m}
      {:valid?       (boolean (:valid m))
       :country      (:countryCode m)
       :vat-number   (:vatNumber m)
       :name         (:name m)
       :address      (:address m)
       :request-date (:requestDate m)
       :raw          m})))

(def ^:private client
  (delay (.. (HttpClient/newBuilder) (connectTimeout (Duration/ofSeconds 10)) (build))))

(defn check
  "Look up a VAT number against the live EU VIES service. Accepts a full VAT id
  with country prefix (\"DE136695976\") or an explicit `country` + `number`.
  Returns `{:valid? :country :vat-number :name :address :request-date :raw}` on a
  reply, or `{:error <message>}` on a network/service failure. Performs a network
  request; requires JDK 11+."
  ([vat] (let [[c n] (split-vat vat)] (check c n)))
  ([country number]
   (try
     (let [payload (json/write-str {:countryCode country :vatNumber number})
           req (.. (HttpRequest/newBuilder (URI/create endpoint))
                   (timeout (Duration/ofSeconds 20))
                   (header "Content-Type" "application/json")
                   (header "Accept" "application/json")
                   (POST (HttpRequest$BodyPublishers/ofString payload))
                   (build))
           resp (.send ^HttpClient @client req (HttpResponse$BodyHandlers/ofString))
           status (.statusCode ^HttpResponse resp)]
       (if (= 200 status)
         (parse-response (.body ^HttpResponse resp))
         {:error (str "VIES returned HTTP " status) :status status}))
     (catch Exception e {:error (.getMessage e)}))))
