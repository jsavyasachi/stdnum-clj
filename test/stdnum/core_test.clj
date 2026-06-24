(ns stdnum.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [stdnum.core :as stdnum]))

;; Vectors are published reference values: standard test card PANs, a worked
;; IBAN, the canonical ISBN/ISIN/ISSN examples, a real ABA routing number, and
;; a Luhn-valid test IMEI.

(deftest credit-cards
  (testing "valid PANs per network"
    (is (stdnum/valid? :credit-card "4111111111111111"))      ; Visa
    (is (stdnum/valid? :credit-card "5555555555554444"))      ; Mastercard
    (is (stdnum/valid? :credit-card "378282246310005"))       ; Amex
    (is (stdnum/valid? :credit-card "6011111111111117"))      ; Discover
    (is (stdnum/valid? :credit-card "30569309025904")))       ; Diners
  (testing "tolerates spaces/hyphens"
    (is (stdnum/valid? :credit-card "4111 1111 1111 1111"))
    (is (stdnum/valid? :credit-card "4111-1111-1111-1111")))
  (testing "rejects bad check digit / junk"
    (is (not (stdnum/valid? :credit-card "4111111111111112")))
    (is (not (stdnum/valid? :credit-card "nonsense")))
    (is (not (stdnum/valid? :credit-card nil))))
  (testing "network detection via parse"
    (is (= :visa (:network (stdnum/parse :credit-card "4111111111111111"))))
    (is (= :amex (:network (stdnum/parse :credit-card "378282246310005"))))
    (is (false? (:valid? (stdnum/parse :credit-card "4111111111111112"))))))

(deftest iban-and-bic
  (testing "IBAN validation + parse fields"
    (is (stdnum/valid? :iban "GB82 WEST 1234 5698 7654 32"))
    (is (not (stdnum/valid? :iban "GB82 WEST 1234 5698 7654 33")))
    (let [p (stdnum/parse :iban "GB82WEST12345698765432")]
      (is (:valid? p))
      (is (= "GB" (:country p)))
      (is (= "WEST12345698765432" (:bban p)))))
  (testing "IBAN format groups in fours"
    (is (= "GB82 WEST 1234 5698 7654 32" (stdnum/format :iban "GB82WEST12345698765432"))))
  (testing "BIC"
    (is (stdnum/valid? :bic "DEUTDEFF"))
    (is (stdnum/valid? :bic "DEUTDEFF500"))
    (is (not (stdnum/valid? :bic "XX")))))

(deftest books-and-securities
  (testing "ISBN-10 and ISBN-13"
    (is (stdnum/valid? :isbn "0306406152"))
    (is (stdnum/valid? :isbn "9780306406157"))
    (is (stdnum/valid? :isbn "978-0-306-40615-7"))
    (is (not (stdnum/valid? :isbn "9780306406158"))))
  (testing "ISSN (needs the hyphen canonically; we normalize either way)"
    (is (stdnum/valid? :issn "0317-8471"))
    (is (stdnum/valid? :issn "03178471"))
    (is (not (stdnum/valid? :issn "0317-8470"))))
  (testing "ISIN"
    (is (stdnum/valid? :isin "US0378331005"))
    (is (not (stdnum/valid? :isin "US0378331004")))))

(deftest bank-routing-and-devices
  (testing "ABA US bank routing number"
    (is (stdnum/valid? :aba "021000021"))
    (is (not (stdnum/valid? :aba "021000020"))))
  (testing "IMEI (Luhn over 15 digits)"
    (is (stdnum/valid? :imei "490154203237518"))
    (is (not (stdnum/valid? :imei "490154203237519"))))
  (testing "generic Luhn primitive"
    (is (stdnum/valid? :luhn "79927398713"))
    (is (not (stdnum/valid? :luhn "79927398714")))))

(deftest detect-and-unknown
  (testing "detect returns the plausible types for a value"
    (is (some #{:credit-card} (stdnum/detect "4111111111111111")))
    (is (empty? (stdnum/detect "nonsense"))))
  (testing "unknown identifier type throws (caller bug, not bad data)"
    (is (thrown? IllegalArgumentException (stdnum/valid? :not-a-type "x"))))
  (testing "valid? never throws on bad data, only returns false"
    (is (false? (stdnum/valid? :iban "")))
    (is (false? (stdnum/valid? :credit-card "")))))
