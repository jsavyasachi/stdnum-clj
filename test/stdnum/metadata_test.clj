(ns stdnum.metadata-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [stdnum.core :as stdnum]))

;; Call directly, not with ns-resolve. A var that becomes private or disappears
;; must break compilation here. It must not change an assertion to nil = nil.

(def corpus
  (edn/read-string (slurp (io/resource "stdnum/vectors.edn"))))

(deftest category-totality
  (let [allowed #{:banking :securities :publishing :commerce :research :vat :national}]
    (doseq [type stdnum/types]
      (is (contains? allowed (stdnum/type-category type))
          (str type " must have a known category")))))

(deftest country-strictness
  (is (= :de (stdnum/type-country :de-vat)))
  (is (= :eu (stdnum/type-country :eu-vat)))
  (is (nil? (stdnum/type-country :credit-card)))
  (is (nil? (stdnum/type-country :iban)))
  (let [iso (set (map (comp keyword str/lower-case) (java.util.Locale/getISOCountries)))]
    (doseq [type stdnum/types
            :let [country (stdnum/type-country type)]
            :when country]
      (is (or (= :eu country) (contains? iso country))
          (str type " has an invalid country " country)))))

(deftest example-totality
  (doseq [type stdnum/types]
    (let [value (stdnum/example type)]
      (is (and (string? value) (not (str/blank? value)))
          (str type " must have a non-blank example"))
      (is (stdnum/valid? type value)
          (str type " example must validate")))))

(deftest examples-and-describe
  (doseq [[type {:keys [valid source]}] corpus]
    (is (= (vec valid) (stdnum/examples type)))
    (is (= (first valid) (stdnum/example type)))
    (is (= #{:type :category :country :example :source}
           (set (keys (stdnum/describe type)))))
    (is (= {:type type
            :category (stdnum/type-category type)
            :country (stdnum/type-country type)
            :example (first valid)
            :source source}
           (stdnum/describe type)))))

(deftest metadata-unknown-type
  (is (thrown? IllegalArgumentException (stdnum/type-category :no-such-type)))
  (is (thrown? IllegalArgumentException (stdnum/type-country :no-such-type)))
  (is (thrown? IllegalArgumentException (stdnum/example :no-such-type)))
  (is (thrown? IllegalArgumentException (stdnum/examples :no-such-type)))
  (is (thrown? IllegalArgumentException (stdnum/describe :no-such-type))))

(deftest detect-country-filter-is-strict
  (is (some #{:credit-card} (stdnum/detect "4111111111111111")))
  (is (= [] (stdnum/detect "4111111111111111" {:country "de"})))
  (is (= [] (stdnum/detect "4111111111111111" {:country :de}))))
