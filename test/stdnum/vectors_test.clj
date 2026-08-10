(ns stdnum.vectors-test
  "The corpus in `vectors.edn` is the source of truth. The test suite uses it.
  Every valid vector must validate. Every invalid vector must fail validation.
  Every entry must cite a `:source`. The integration run checks VIES-tagged EU
  VAT numbers against the live registry."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [stdnum.core :as stdnum]
            [stdnum.vies :as vies]))

(def corpus (edn/read-string (slurp (io/resource "stdnum/vectors.edn"))))

(deftest corpus-vectors
  (testing "every corpus type is a real identifier type that cites a source"
    (doseq [[t entry] corpus]
      (is (contains? stdnum/types t) (str t " is not a known type"))
      (is (string? (:source entry)) (str t " is missing a :source citation"))))
  (testing "every valid vector validates; every invalid vector does not"
    (doseq [[t {:keys [valid invalid]}] corpus]
      (doseq [v valid]   (is (stdnum/valid? t v) (str t " should accept " v)))
      (doseq [v invalid] (is (not (stdnum/valid? t v)) (str t " should reject " v))))))

(deftest corpus-coverage
  (testing "report shipped types without a corpus vector"
    (let [uncovered (sort (remove (set (keys corpus)) stdnum/types))]
      (when (seq uncovered)
        (println "[vectors] types without a corpus vector yet:" (vec uncovered)))
      ;; The corpus must cover most shipped types.
      (is (>= (count corpus) (int (* 0.75 (count stdnum/types))))
          "corpus should cover at least 75% of shipped types"))))

;; The integration test checks VIES-tagged VAT numbers against the live EU service.
;; The default test run skips it. Run `lein test :integration` to run it.
(deftest ^:integration vies-source-of-truth
  (doseq [[t {:keys [valid vies]}] corpus :when vies, v valid]
    (let [r (vies/check v)]
      (testing (str t " " v " against live VIES")
        ;; Accept member-state outages. Check the result when the service replies.
        (is (or (:error r) (:valid? r))
            (str v " unexpectedly reported invalid by VIES: " (pr-str r)))))))
