(ns stdnum.checkdigit-test
  (:require [clojure.test :refer [deftest testing is]]
            [stdnum.checkdigit :as cd]))

(deftest luhn
  (testing "validate a full number"
    (is (cd/luhn-valid? "79927398713"))
    (is (not (cd/luhn-valid? "79927398714")))
    (is (not (cd/luhn-valid? "abc"))))
  (testing "compute the check digit for a payload"
    (is (= "3" (cd/luhn-check-digit "7992739871")))))

(deftest verhoeff
  (testing "validate (India Aadhaar uses Verhoeff)"
    (is (cd/verhoeff-valid? "234123412346"))
    (is (not (cd/verhoeff-valid? "234123412347"))))
  (testing "compute the check digit"
    (is (= "6" (cd/verhoeff-check-digit "23412341234")))))

(deftest iso7064-mod11-2
  (testing "validate (ORCID / ISNI / ISBN-10 check char, may be X)"
    (is (cd/iso7064-mod11-2-valid? "0000000218250097"))
    (is (not (cd/iso7064-mod11-2-valid? "0000000218250098"))))
  (testing "compute the check character"
    (is (= "7" (cd/iso7064-mod11-2-check "000000021825009")))))

(deftest iso7064-mod97-10
  (testing "validate over alphanumerics (LEI / IBAN family); valid when remainder is 1"
    (is (cd/iso7064-mod97-10-valid? "5493001KJTIIGC8Y1R12"))   ; a real GLEIF LEI
    (is (not (cd/iso7064-mod97-10-valid? "5493001KJTIIGC8Y1R13")))))
