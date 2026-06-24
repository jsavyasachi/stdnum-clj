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

(deftest global-and-national-ids
  (testing "LEI (ISO 17442, mod-97-10) - real GLEIF values"
    (is (stdnum/valid? :lei "5493001KJTIIGC8Y1R12"))
    (is (stdnum/valid? :lei "HWUPKR0MPOU8FGXBT394"))
    (is (not (stdnum/valid? :lei "5493001KJTIIGC8Y1R13")))   ; bad check
    (is (not (stdnum/valid? :lei "5493001KJTIIGC8Y1R1"))))   ; too short
  (testing "Brazil CPF"
    (is (stdnum/valid? :br-cpf "111.444.777-35"))
    (is (stdnum/valid? :br-cpf "11144477735"))
    (is (not (stdnum/valid? :br-cpf "11144477736")))         ; bad check
    (is (not (stdnum/valid? :br-cpf "11111111111")))         ; repeated-digit
    (is (= "111.444.777-35" (stdnum/format :br-cpf "11144477735"))))
  (testing "Brazil CNPJ"
    (is (stdnum/valid? :br-cnpj "11.222.333/0001-81"))
    (is (stdnum/valid? :br-cnpj "00000000000191"))           ; Banco do Brasil
    (is (not (stdnum/valid? :br-cnpj "11222333000182")))     ; bad check
    (is (not (stdnum/valid? :br-cnpj "11111111111111")))     ; repeated-digit
    (is (= "11.222.333/0001-81" (stdnum/format :br-cnpj "11222333000181")))))

(deftest securities-and-us
  (testing "CUSIP (engine check digit)"
    (is (stdnum/valid? :cusip "037833100"))                  ; Apple
    (is (stdnum/valid? :cusip "38259P508"))                  ; Alphabet
    (is (not (stdnum/valid? :cusip "037833108"))))           ; bad check
  (testing "SEDOL"
    (is (stdnum/valid? :sedol "0263494"))
    (is (stdnum/valid? :sedol "B0YBKJ7"))
    (is (not (stdnum/valid? :sedol "0263495"))))
  (testing "US SSN structural rules"
    (is (stdnum/valid? :us-ssn "123-45-6789"))
    (is (= "123-45-6789" (stdnum/format :us-ssn "123456789")))
    (is (not (stdnum/valid? :us-ssn "000-12-3456")))         ; area 000
    (is (not (stdnum/valid? :us-ssn "666-12-3456")))         ; area 666
    (is (not (stdnum/valid? :us-ssn "900-12-3456")))         ; area 9xx
    (is (not (stdnum/valid? :us-ssn "123-00-6789")))         ; group 00
    (is (not (stdnum/valid? :us-ssn "123-45-0000")))         ; serial 0000
    (is (not (stdnum/valid? :us-ssn "078-05-1120"))))        ; SSA promo number
  (testing "US EIN prefix rules"
    (is (stdnum/valid? :us-ein "12-3456789"))
    (is (= "12-3456789" (stdnum/format :us-ein "123456789")))
    (is (not (stdnum/valid? :us-ein "07-0000000")))          ; 07 not an IRS prefix
    (is (not (stdnum/valid? :us-ein "00-0000000")))))

(deftest eu-uk-vat-and-nino
  (testing "VAT numbers validate with and without the country prefix (real published values)"
    (is (stdnum/valid? :de-vat "DE136695976"))
    (is (stdnum/valid? :de-vat "136695976"))
    (is (not (stdnum/valid? :de-vat "136695977")))
    (is (stdnum/valid? :fr-vat "FR40303265045"))
    (is (not (stdnum/valid? :fr-vat "FR41303265045")))
    (is (stdnum/valid? :it-vat "IT00743110157"))
    (is (not (stdnum/valid? :it-vat "00743110158")))
    (is (stdnum/valid? :be-vat "BE0417497106"))
    (is (not (stdnum/valid? :be-vat "0417497107")))
    (is (stdnum/valid? :pl-vat "PL5260001246"))
    (is (not (stdnum/valid? :pl-vat "5260001247")))
    (is (stdnum/valid? :gb-vat "GB980780684"))
    (is (not (stdnum/valid? :gb-vat "980780685"))))
  (testing "UK NINO structural rules"
    (is (stdnum/valid? :gb-nino "AB123456C"))
    (is (stdnum/valid? :gb-nino "AB123456"))             ; suffix optional
    (is (not (stdnum/valid? :gb-nino "QQ123456C")))      ; Q not allowed
    (is (not (stdnum/valid? :gb-nino "GB123456C")))      ; disallowed prefix
    (is (not (stdnum/valid? :gb-nino "AO123456C")))))    ; O not allowed as 2nd letter

(deftest other-national-ids
  (testing "Canada SIN (Luhn)"
    (is (stdnum/valid? :ca-sin "046454286"))
    (is (not (stdnum/valid? :ca-sin "046454287"))))
  (testing "Australia ABN (weighted mod 89) - ATO published example"
    (is (stdnum/valid? :au-abn "51824753556"))
    (is (not (stdnum/valid? :au-abn "51824753557"))))
  (testing "India PAN (structural, entity-type letter)"
    (is (stdnum/valid? :in-pan "ABCPE1234F"))
    (is (not (stdnum/valid? :in-pan "ABCDE1234F"))))   ; D is not a valid entity type
  (testing "India Aadhaar (Verhoeff)"
    (is (stdnum/valid? :in-aadhaar "234123412346"))
    (is (not (stdnum/valid? :in-aadhaar "234123412347")))))

(deftest more-national-ids
  (testing "Spain DNI / NIE (mod-23 control letter)"
    (is (stdnum/valid? :es-dni "12345678Z"))
    (is (not (stdnum/valid? :es-dni "12345678A")))
    (is (stdnum/valid? :es-nie "X1234567L"))
    (is (not (stdnum/valid? :es-nie "X1234567A"))))
  (testing "Netherlands BSN (elfproef)"
    (is (stdnum/valid? :nl-bsn "111222333"))
    (is (not (stdnum/valid? :nl-bsn "111222334"))))
  (testing "China resident ID (ISO 7064 MOD 11-2, X check)"
    (is (stdnum/valid? :cn-ric "11010519491231002X"))
    (is (stdnum/valid? :cn-ric "440524188001010014"))
    (is (not (stdnum/valid? :cn-ric "11010519491231002Y"))))
  (testing "Sweden personnummer (Luhn)"
    (is (stdnum/valid? :se-pnr "811218-9876"))            ; separator tolerated
    (is (not (stdnum/valid? :se-pnr "8112189877")))))

(deftest banking-and-more-national-ids
  (testing "Mexico CLABE bank account (weighted mod 10)"
    (is (stdnum/valid? :mx-clabe "002010077777777771"))
    (is (stdnum/valid? :mx-clabe "032180000118359719"))
    (is (not (stdnum/valid? :mx-clabe "002010077777777772"))))
  (testing "South Africa ID (Luhn)"
    (is (stdnum/valid? :za-id "8001015009087"))
    (is (not (stdnum/valid? :za-id "8001015009088"))))
  (testing "Norway organisasjonsnummer (mod 11)"
    (is (stdnum/valid? :no-org "974760673"))
    (is (not (stdnum/valid? :no-org "974760674"))))
  (testing "Turkey TC Kimlik No"
    (is (stdnum/valid? :tr-tc "10000000146"))
    (is (not (stdnum/valid? :tr-tc "10000000147")))
    (is (not (stdnum/valid? :tr-tc "01000000146")))))   ; first digit 0

(deftest more-eu-vat
  (testing "Austria ATU (prefix optional)"
    (is (stdnum/valid? :at-vat "ATU13585627"))
    (is (stdnum/valid? :at-vat "13585627"))
    (is (not (stdnum/valid? :at-vat "ATU13585628"))))
  (testing "Denmark VAT"
    (is (stdnum/valid? :dk-vat "DK13585628"))
    (is (not (stdnum/valid? :dk-vat "13585629"))))
  (testing "Finland VAT"
    (is (stdnum/valid? :fi-vat "FI20774740"))
    (is (not (stdnum/valid? :fi-vat "20774741"))))
  (testing "Sweden VAT (Luhn org number + 01 suffix)"
    (is (stdnum/valid? :se-vat "SE556293998201"))
    (is (not (stdnum/valid? :se-vat "556293998101")))   ; wrong suffix
    (is (not (stdnum/valid? :se-vat "556293998202")))))  ; bad check

(deftest more-tax-and-company-ids
  (testing "Greece VAT (AFM), EL prefix optional"
    (is (stdnum/valid? :gr-vat "EL094014249"))
    (is (not (stdnum/valid? :gr-vat "094014248"))))
  (testing "Portugal NIF"
    (is (stdnum/valid? :pt-nif "501964843"))
    (is (not (stdnum/valid? :pt-nif "501964844"))))
  (testing "Czech IČO"
    (is (stdnum/valid? :cz-ico "00006947"))
    (is (not (stdnum/valid? :cz-ico "00006948"))))
  (testing "Japan corporate number"
    (is (stdnum/valid? :jp-cn "7000012050002"))
    (is (not (stdnum/valid? :jp-cn "7000012050003")))))

(deftest tfn-vat-gstin
  (testing "Australia TFN"
    (is (stdnum/valid? :au-tfn "123456782"))
    (is (not (stdnum/valid? :au-tfn "123456789"))))
  (testing "Luxembourg VAT"
    (is (stdnum/valid? :lu-vat "LU26375245"))
    (is (not (stdnum/valid? :lu-vat "26375246"))))
  (testing "Slovenia VAT"
    (is (stdnum/valid? :si-vat "SI50223054"))
    (is (not (stdnum/valid? :si-vat "50223055"))))
  (testing "India GSTIN (base-36 check char)"
    (is (stdnum/valid? :in-gstin "27AAPFU0939F1ZV"))
    (is (not (stdnum/valid? :in-gstin "27AAPFU0939F1ZX")))))

(deftest more-eu-coverage
  (testing "Estonia VAT"
    (is (stdnum/valid? :ee-vat "EE100207415"))
    (is (not (stdnum/valid? :ee-vat "100207416"))))
  (testing "Hungary VAT"
    (is (stdnum/valid? :hu-vat "HU10597190"))
    (is (not (stdnum/valid? :hu-vat "10597191"))))
  (testing "Croatia OIB (ISO 7064 MOD 11,10)"
    (is (stdnum/valid? :hr-oib "69435151530"))
    (is (not (stdnum/valid? :hr-oib "69435151531")))))

(deftest codice-fiscale-and-swiss
  (testing "Italy codice fiscale (mod-26 check letter)"
    (is (stdnum/valid? :it-cf "RSSMRA80A01H501U"))
    (is (stdnum/valid? :it-cf "MRTMTT25D09F205Z"))
    (is (not (stdnum/valid? :it-cf "RSSMRA80A01H501A"))))
  (testing "Switzerland UID (CHE prefix optional)"
    (is (stdnum/valid? :ch-uid "CHE-116.281.710"))
    (is (not (stdnum/valid? :ch-uid "116281711"))))
  (testing "Switzerland AHV"
    (is (stdnum/valid? :ch-ahv "756.9217.0769.85"))
    (is (not (stdnum/valid? :ch-ahv "7569217076986")))))

(deftest nz-be-fi
  (testing "New Zealand IRD"
    (is (stdnum/valid? :nz-ird "049098576"))
    (is (stdnum/valid? :nz-ird "49091850"))
    (is (not (stdnum/valid? :nz-ird "049098577"))))
  (testing "Belgium national number"
    (is (stdnum/valid? :be-nn "00012511148"))
    (is (not (stdnum/valid? :be-nn "00012511149"))))
  (testing "Finland HETU (century sign optional after normalization)"
    (is (stdnum/valid? :fi-hetu "131052-308T"))
    (is (stdnum/valid? :fi-hetu "010594Y9032"))
    (is (not (stdnum/valid? :fi-hetu "131052-308U")))))

(deftest detect-and-unknown
  (testing "detect returns the plausible types for a value"
    (is (some #{:credit-card} (stdnum/detect "4111111111111111")))
    (is (empty? (stdnum/detect "nonsense"))))
  (testing "unknown identifier type throws (caller bug, not bad data)"
    (is (thrown? IllegalArgumentException (stdnum/valid? :not-a-type "x"))))
  (testing "valid? never throws on bad data, only returns false"
    (is (false? (stdnum/valid? :iban "")))
    (is (false? (stdnum/valid? :credit-card "")))))
