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

(def ^:private endpoint
  "https://ec.europa.eu/taxation_customs/vies/rest-api/check-vat-number")

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
  with a country prefix (\"DE136695976\"), or an explicit `country` and `number`.
  Returns `{:valid? :country :vat-number :name :address :request-date :raw}` on a
  reply. Returns `{:error <message>}` on a network failure or a service failure.
  This function does a network request and requires JDK 11+."
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
