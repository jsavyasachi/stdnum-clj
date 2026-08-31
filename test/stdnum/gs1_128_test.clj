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

(deftest logistics-application-identifiers
  (testing "consignment, shipment, and routing identifiers"
    (is (gs1/valid? "(401)CONSIGNMENT-123"))
    (is (gs1/valid? "(402)12345678901234567"))
    (is (gs1/valid? "(403)ROUTE-SEA-01"))
    (is (not (gs1/valid? "(402)1234567890123456A")))
    (is (not (gs1/valid? "(401)"))))
  (testing "logistics location GLN roles"
    (is (gs1/valid? "(411)0614141000005"))
    (is (gs1/valid? "(413)0614141000005"))
    (is (gs1/valid? "(415)0614141000005"))
    (is (gs1/valid? "(416)0614141000005"))
    (is (gs1/valid? "(417)0614141000005"))
    (is (not (gs1/valid? "(411)061414100000")))
    (is (not (gs1/valid? "(413)06141410000A5"))))
  (testing "ship-to postal codes"
    (is (gs1/valid? "(420)94107"))
    (is (gs1/valid? "(421)84094107"))
    (is (not (gs1/valid? "(420)123456789012345678901")))
    (is (not (gs1/valid? "(421)84A94107"))))
  (testing "logistics dimensions and ITIP"
    (is (gs1/valid? "(3321)000123"))
    (is (gs1/valid? "(3332)000456"))
    (is (gs1/valid? "(3363)000789"))
    (is (gs1/valid? "(8001)01234567890123"))
    (is (gs1/valid? "(8006)012345678901230105"))
    (is (gs1/valid? "(8026)012345678901230105"))
    (is (not (gs1/valid? "(3321)00012A")))
    (is (not (gs1/valid? "(8006)01234567890123010")))))

(deftest invalid-element-strings
  (testing "raw application identifiers must be numeric"
    (is (= {:valid? false} (gs1/parse "310A000123"))))
  (testing "an unknown AI after a valid segment is not silently dropped"
    (is (= {:valid? false}
           (gs1/parse "010952123454321399ABC"))))
  (testing "a fixed-length value must contain every required character"
    (is (= {:valid? false}
           (gs1/parse "01095212345432"))))
  (testing "a variable-length value that runs past its maximum lacks its FNC1 separator"
    (is (= {:valid? false}
           (gs1/parse "10ABC12317170331"))))
  (testing "a trailing raw tail is not silently ignored"
    (is (= {:valid? false}
           (gs1/parse "0109521234543213XYZ"))))
  (testing "an empty parenthesized value is invalid"
    (is (= {:valid? false}
           (gs1/parse "(10)"))))
  (testing "non-numeric implied-decimal data returns an invalid result"
    (is (= {:valid? false}
           (gs1/parse "(3103)ABCDEF"))))
  (testing "numeric fixed-length data rejects alphabetic values"
    (is (= {:valid? false}
           (gs1/parse "(17)ABCDEF")))))
