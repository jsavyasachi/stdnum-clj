(ns stdnum.gs1-128-test
  (:require [clojure.test :refer [deftest testing is]]
            [stdnum.gs1-128 :as gs1]))

(deftest parenthesized
  (testing "splits a human-readable element string into labeled AI segments"
    (let [r (gs1/parse "(01)09521234543213(15)170331(10)ABC123")]
      (is (= 3 (count r)))
      (is (= {:ai "01" :label "GTIN" :value "09521234543213"} (dissoc (nth r 0) :decimals)))
      (is (= "15" (:ai (nth r 1))))
      (is (= "BEST BEFORE" (:label (nth r 1))))
      (is (= "ABC123" (:value (nth r 2))))))
  (testing "decodes the implied decimal place of weight/measure AIs"
    (let [w (first (gs1/parse "(3103)000123"))]
      (is (= "3103" (:ai w)))
      (is (= "000123" (:value w)))
      (is (= 3 (:decimals w)))
      (is (= 0.123 (:decimal-value w)))
      (is (= "NET WEIGHT (kg)" (:label w)))))
  (testing "amount-payable family is variable length with decimals"
    (let [a (first (gs1/parse "(3922)0399"))]
      (is (= "3922" (:ai a)))
      (is (= 2 (:decimals a)))
      (is (= 3.99 (:decimal-value a))))))

(def ^:private fnc1 (str (char 29)))                  ; ASCII group separator

(deftest raw-fnc1
  (testing "parses the raw form using fixed lengths, FNC1 ending variable fields"
    (let [r (gs1/parse (str "0109521234543213" "10ABC123" fnc1 "17170331"))]
      (is (= ["01" "10" "17"] (mapv :ai r)))
      (is (= "09521234543213" (:value (nth r 0))))
      (is (= "ABC123" (:value (nth r 1))))             ; ended by FNC1
      (is (= "170331" (:value (nth r 2)))))))

(deftest as-map
  (testing "parse-map keys by AI string"
    (is (= "09521234543213" (get (gs1/parse-map "(01)09521234543213(10)ABC") "01")))))
