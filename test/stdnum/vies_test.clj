(ns stdnum.vies-test
  (:require [clojure.test :refer [deftest testing is]]
            [stdnum.vies :as vies]))

;; The response parser is pure and is unit-tested with canned JSON (no network).
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
      (is (not (contains? r :valid?))))))

;; The live lookup hits the EU service; excluded from the default run. Run with
;; `lein test :integration`. Lenient because VIES is frequently unavailable.
(deftest ^:integration live-check
  (testing "live VIES lookup returns a shaped result (or a graceful :error)"
    (let [r (vies/check "DE136695976")]
      (is (or (:error r) (contains? r :valid?))))))
