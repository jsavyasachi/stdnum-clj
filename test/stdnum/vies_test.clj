(ns stdnum.vies-test
  (:require [clojure.test :refer [deftest testing is]]
            [stdnum.vies :as vies]))

(def valid-body
  "{\"countryCode\":\"DE\",\"vatNumber\":\"136695976\",\"valid\":true}")

;; The response parser is pure. The unit test uses saved JSON and no network.
(deftest parse-response
  (testing "a valid VIES reply with trader details"
    (let [r (vies/parse-response
             "{\"countryCode\":\"DE\",\"vatNumber\":\"136695976\",\"requestDate\":\"2026-06-24+02:00\",\"valid\":true,\"name\":\"ACME GmbH\",\"address\":\"Berlin\"}")]
      (is (true? (:valid? r)))
      (is (= "DE" (:country r)))
      (is (= "136695976" (:vat-number r)))
      (is (= "ACME GmbH" (:name r)))
      (is (= "Berlin" (:address r)))))
  (testing "an invalid reply"
    (let [r (vies/parse-response "{\"countryCode\":\"DE\",\"vatNumber\":\"000000000\",\"valid\":false,\"name\":\"---\"}")]
      (is (false? (:valid? r)))
      (is (= "DE" (:country r)))))
  (testing "a member-state error becomes :error, not :valid? false (validity unknown)"
    (let [r (vies/parse-response "{\"actionSucceed\":false,\"errorWrappers\":[{\"error\":\"MS_UNAVAILABLE\"}]}")]
      (is (= "MS_UNAVAILABLE" (:error r)))
      (is (not (contains? r :valid?)))))
  (testing "a response without an explicit validity field is an error"
    (let [r (vies/parse-response "{}")]
      (is (:error r))
      (is (not (contains? r :valid?))))))

(deftest configurable-transport-and-request
  (let [requests (atom [])
        r (vies/check "DE" "136695976"
                      {:endpoint "https://example.test/vat"
                       :timeout-ms 3210
                       :max-retries 0
                       :transport (fn [request]
                                    (swap! requests conj request)
                                    {:status 200 :headers {"X-Test" "ok"}
                                     :body valid-body})})]
    (is (:valid? r))
    (is (= "https://example.test/vat" (:url (first @requests))))
    (is (= 3210 (:timeout-ms (first @requests))))
    (is (= {:countryCode "DE" :vatNumber "136695976"}
           (:body (first @requests))))))

(deftest retries-transient-failures-with-injected-backoff
  (let [calls (atom 0)
        delays (atom [])
        r (vies/check "DE136695976"
                      {:max-retries 2
                       :backoff-ms [10 20]
                       :sleep-fn #(swap! delays conj %)
                       :transport (fn [_]
                                    (if (= 3 (swap! calls inc))
                                      {:status 200 :headers {} :body valid-body}
                                      {:status 503 :headers {} :body "unavailable"}))})]
    (is (:valid? r))
    (is (= 3 @calls))
    (is (= [10 20] @delays))))

(deftest rate-limit-errors-preserve-code-and-response-metadata
  (let [r (vies/check "DE136695976"
                      {:max-retries 0
                       :transport (fn [_]
                                    {:status 429
                                     :headers {"Retry-After" "7" "X-Request-Id" "req-1"}
                                     :body "{\"actionSucceed\":false,\"errorWrappers\":[{\"error\":\"GLOBAL_MAX_CONCURRENT_REQ\"}]}"})})]
    (is (= "GLOBAL_MAX_CONCURRENT_REQ" (:error r)))
    (is (= 429 (:status r)))
    (is (= "7" (get-in r [:headers "Retry-After"])))
    (is (= "7" (get-in r [:rate-limit :retry-after])))
    (is (= "req-1" (get-in r [:rate-limit :headers "X-Request-Id"])))))

(deftest nonretryable-response-is-requested-once
  (let [calls (atom 0)
        delays (atom [])
        r (vies/check "DE136695976"
                      {:max-retries 3
                       :sleep-fn #(swap! delays conj %)
                       :transport (fn [_]
                                    (swap! calls inc)
                                    {:status 400 :headers {} :body "bad request"})})]
    (is (= "VIES returned HTTP 400" (:error r)))
    (is (= 1 @calls))
    (is (empty? @delays))))

(deftest non-success-response-with-empty-json-is-unknown
  (let [r (vies/check "DE136695976"
                      {:max-retries 0
                       :transport (fn [_] {:status 503 :headers {} :body "{}"})})]
    (is (:error r))
    (is (not (contains? r :valid?)))
    (is (= 503 (:status r)))))

(deftest retries-transport-exceptions
  (let [calls (atom 0)
        delays (atom [])
        r (vies/check "DE136695976"
                      {:max-retries 1
                       :backoff-ms [5]
                       :sleep-fn #(swap! delays conj %)
                       :transport (fn [_]
                                    (if (= 2 (swap! calls inc))
                                      {:status 200 :headers {} :body valid-body}
                                      (throw (java.io.IOException. "timed out"))))})]
    (is (:valid? r))
    (is (= 2 @calls))
    (is (= [5] @delays))))

;; The live lookup uses the EU service. The default run excludes it. Run
;; `lein test :integration`. VIES can be unavailable.
(deftest ^:integration live-check
  (testing "live VIES lookup returns a shaped result (or a graceful :error)"
    (let [r (vies/check "DE136695976")]
      (is (or (:error r) (contains? r :valid?))))))
