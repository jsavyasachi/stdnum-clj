(ns stdnum.generative-testing-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [stdnum.checkdigit :as cd]
            [stdnum.core :as stdnum]))

(set! *warn-on-reflection* true)

(def corpus (edn/read-string (slurp (io/resource "stdnum/vectors.edn"))))

(defn vector-values [types]
  (mapcat #(get-in corpus [% :valid]) types))

(defn replace-at [s i c]
  (str (subs s 0 i) c (subs s (inc i))))

(def digit-chars (mapv char (range (int \0) (inc (int \9)))))
(def alpha-num-chars (vec (str "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")))

(defn mutation-cases [algorithm types]
  (for [value (vector-values types)
        i (range (count value))
        replacement (if (= algorithm :mod97)
                      (remove #{(nth value i)} alpha-num-chars)
                      (remove #{(nth value i)} digit-chars))]
    [value i replacement]))

(defn mutation-generator [algorithm types]
  (gen/elements (for [[value i replacement] (mutation-cases algorithm types)]
                 (replace-at value i replacement))))

(defn passes? [property]
  (:pass? (tc/quick-check 100 property :seed 424242)))

(deftest checksum-mutations-fail
  (testing "one-character mutations of valid Luhn identifiers fail"
    (is (passes? (prop/for-all [mutated (mutation-generator :digits
                                                              [:credit-card :imei :luhn :ca-sin])]
                   (not (cd/luhn-valid? mutated))))))
  (testing "one-character mutations of valid Verhoeff identifiers fail"
    (is (passes? (prop/for-all [mutated (mutation-generator :digits [:in-aadhaar])]
                   (not (cd/verhoeff-valid? mutated))))))
  (testing "one-character mutations of valid Mod 11-2 identifiers fail"
    (is (passes? (prop/for-all [mutated (mutation-generator :digits [:orcid :isni])]
                   (not (cd/iso7064-mod11-2-valid? mutated))))))
  (testing "one-character mutations of valid Mod 97-10 identifiers fail"
    (is (passes? (prop/for-all [mutated (mutation-generator :mod97 [:lei :iban])]
                   (not (cd/iso7064-mod97-10-valid? mutated)))))))

(def separator-cases
  (for [type [:credit-card :iban :issn]
        value (get-in corpus [type :valid])]
    [type (str/replace value #"[\s-]" "")]))

(def separator-generator (gen/elements separator-cases))

(defn insert-separators [s separators]
  (apply str (mapcat (fn [[i c]] [c (get separators i "")]) (map-indexed vector s))))

(def separator-input-generator
  (gen/bind separator-generator
            (fn [[type compact]]
              (gen/fmap #(vector type (insert-separators compact %))
                        (gen/vector (gen/elements [" " "-"]) (dec (count compact)))))))

(deftest documented-separators-preserve-validity
  (is (passes? (prop/for-all [[type separated] separator-input-generator]
                 (stdnum/valid? type separated)))))

(deftest date-boundaries
  (testing "valid leap-day and month-end dates remain valid"
    (doseq [value ["000229-02-1234" "000430-02-1234" "991231-02-1234"]]
      (is (stdnum/valid? :my-nric value) value)))
  (testing "impossible leap-day and month-end dates fail"
    (doseq [value ["000230-02-1234" "000431-02-1234" "991332-02-1234"]]
      (is (not (stdnum/valid? :my-nric value)) value)))
  (testing "Indonesian NIK accepts leap day but rejects invalid month/day edges"
    (doseq [value ["3171013002000001" "3171013104000001"]]
      (is (not (stdnum/valid? :id-nik value)) value))
    (is (stdnum/valid? :id-nik "3171012902000001"))
    (is (stdnum/valid? :id-nik "3171022802000001"))))
