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
      (is (= "WEST12345698765432" (:bban p)))
      (is (= "WEST" (:bank-code p)))
      (is (= "123456" (:branch-code p)))
      (is (= "98765432" (:account-number p)))))
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

(deftest figi-and-more-vat
  (testing "FIGI (OMG check digit) - real Bloomberg values"
    (is (stdnum/valid? :figi "BBG000BLNNH6"))            ; Apple composite
    (is (stdnum/valid? :figi "BBG000B9XRY4"))            ; Apple equity
    (is (not (stdnum/valid? :figi "BBG000BLNNH7")))      ; bad check digit
    (is (not (stdnum/valid? :figi "ABG000BLNNH6")))      ; vowel in position 1
    (is (not (stdnum/valid? :figi "BBX000BLNNH6"))))     ; position 3 not 'G'
  (testing "Malta VAT (37-complement)"
    (is (stdnum/valid? :mt-vat "MT11679112"))
    (is (stdnum/valid? :mt-vat "11679112"))
    (is (not (stdnum/valid? :mt-vat "11679113"))))
  (testing "Slovakia VAT (mod 11)"
    (is (stdnum/valid? :sk-vat "SK2020317068"))
    (is (stdnum/valid? :sk-vat "2020317068"))
    (is (not (stdnum/valid? :sk-vat "2020317069"))))
  (testing "Lithuania VAT (9 and 12 digit, weighted mod 11)"
    (is (stdnum/valid? :lt-vat "LT119511515"))
    (is (not (stdnum/valid? :lt-vat "119511516"))))
  (testing "Cyprus VAT (even-position remap, mod-26 letter)"
    (is (stdnum/valid? :cy-vat "CY10259033P"))
    (is (stdnum/valid? :cy-vat "10259033P"))
    (is (not (stdnum/valid? :cy-vat "10259033Q")))))

(deftest apac-and-romania-ids
  (testing "Romania VAT/CUI (key right-aligned, variable length)"
    (is (stdnum/valid? :ro-vat "RO18547290"))
    (is (stdnum/valid? :ro-vat "18547290"))
    (is (not (stdnum/valid? :ro-vat "18547291"))))
  (testing "Singapore NRIC/FIN (S/T citizens-PR, F/G foreigners) - textbook check letters"
    (is (stdnum/valid? :sg-nric "S1234567D"))
    (is (stdnum/valid? :sg-nric "F1234567N"))
    (is (not (stdnum/valid? :sg-nric "S1234567A")))   ; bad check letter
    (is (not (stdnum/valid? :sg-nric "M1234567A"))))  ; M-series out of scope
  (testing "Hong Kong HKID (weighted mod 11, A=10 check)"
    (is (stdnum/valid? :hk-id "A1234563"))            ; canonical worked example A123456(3)
    (is (not (stdnum/valid? :hk-id "A1234564"))))
  (testing "South Korea Business Registration Number - Samsung Electronics"
    (is (stdnum/valid? :kr-brn "1248100998"))
    (is (not (stdnum/valid? :kr-brn "1248100999")))))

(deftest commerce-vehicle-healthcare
  (testing "EAN-13 / GTIN-13 barcode"
    (is (stdnum/valid? :ean13 "4006381333931"))
    (is (not (stdnum/valid? :ean13 "4006381333932"))))
  (testing "UPC-A (12-digit GTIN)"
    (is (stdnum/valid? :upc "036000291452"))
    (is (not (stdnum/valid? :upc "036000291453"))))
  (testing "VIN (ISO 3779 North-American check digit) - NHTSA worked example"
    (is (stdnum/valid? :vin "1HGBH41JXMN109186"))
    (is (not (stdnum/valid? :vin "1HGBH41J1MN109186")))   ; bad check digit
    (is (not (stdnum/valid? :vin "1HGBH41JXMN10918I"))))  ; I is not a legal VIN char
  (testing "UK NHS number (weighted mod 11)"
    (is (stdnum/valid? :nhs "9434765919"))                ; standard NHS test number
    (is (not (stdnum/valid? :nhs "9434765918"))))
  (testing "US NPI (Luhn over the 80840 issuer prefix) - CMS example"
    (is (stdnum/valid? :npi "1234567893"))
    (is (not (stdnum/valid? :npi "1234567890")))
    (is (not (stdnum/valid? :npi "3234567890"))))         ; must begin 1 or 2
  (testing "EAN-8 / GTIN-8 (3-1 weighted)"
    (is (stdnum/valid? :ean8 "96385074"))
    (is (not (stdnum/valid? :ean8 "96385075"))))
  (testing "ISMN (979-0 prefixed EAN-13) - real published ISMN"
    (is (stdnum/valid? :ismn "9790001148412"))
    (is (stdnum/valid? :ismn "979-0-001-14841-2"))         ; separators tolerated
    (is (not (stdnum/valid? :ismn "9790001148413")))
    (is (not (stdnum/valid? :ismn "9780001148412"))))      ; 978 is ISBN, not ISMN
  (testing "CAS Registry Number (chemicals)"
    (is (stdnum/valid? :cas "7732-18-5"))                  ; water
    (is (stdnum/valid? :cas "50-00-0"))                    ; formaldehyde
    (is (not (stdnum/valid? :cas "7732-18-6"))))
  (testing "IMO ship number (weighted mod 10)"
    (is (stdnum/valid? :imo "IMO 9074729"))
    (is (stdnum/valid? :imo "9074729"))
    (is (not (stdnum/valid? :imo "9074728")))))

(deftest national-tax-ids
  (testing "France NIR (social security, mod 97 key) - INSEE worked example"
    (is (stdnum/valid? :fr-nir "255081416802538"))
    (is (not (stdnum/valid? :fr-nir "255081416802539"))))
  (testing "Poland PESEL (weighted mod 10)"
    (is (stdnum/valid? :pl-pesel "44051401359"))
    (is (not (stdnum/valid? :pl-pesel "44051401358"))))
  (testing "Argentina CUIT (weighted mod 11) - MercadoLibre"
    (is (stdnum/valid? :ar-cuit "30-70308853-4"))        ; separators tolerated
    (is (stdnum/valid? :ar-cuit "30703088534"))
    (is (not (stdnum/valid? :ar-cuit "30703088535"))))
  (testing "Chile RUT (mod 11, K check) - Banco de Chile"
    (is (stdnum/valid? :cl-rut "97.004.000-5"))
    (is (stdnum/valid? :cl-rut "970040005"))
    (is (not (stdnum/valid? :cl-rut "970040006"))))
  (testing "Colombia NIT (weighted mod 11) - Bancolombia"
    (is (stdnum/valid? :co-nit "890.903.938-8"))
    (is (stdnum/valid? :co-nit "8909039388"))
    (is (not (stdnum/valid? :co-nit "8909039389")))))

(deftest more-national-ids-2
  (testing "Peru RUC (weighted mod 11) - BCP"
    (is (stdnum/valid? :pe-ruc "20100070970"))
    (is (not (stdnum/valid? :pe-ruc "20100070971"))))
  (testing "Ireland PPS (mod-23 check letter, optional 2nd letter)"
    (is (stdnum/valid? :ie-pps "6433435F"))
    (is (stdnum/valid? :ie-pps "6433435OA"))             ; 2nd letter folded into the check
    (is (not (stdnum/valid? :ie-pps "6433435FA")))       ; wrong check given the 2nd letter
    (is (not (stdnum/valid? :ie-pps "6433435G"))))
  (testing "Estonia isikukood (weighted mod 11, reweight on remainder 10)"
    (is (stdnum/valid? :ee-ik "37605030299"))
    (is (not (stdnum/valid? :ee-ik "37605030298"))))
  (testing "JMBG (shared ex-Yugoslav 13-digit number)"
    (is (stdnum/valid? :jmbg "0101006500006"))
    (is (not (stdnum/valid? :jmbg "0101006500007"))))
  (testing "Ecuador cedula (province 01-24, Luhn-like)"
    (is (stdnum/valid? :ec-ced "1710034065"))
    (is (not (stdnum/valid? :ec-ced "1710034066")))
    (is (not (stdnum/valid? :ec-ced "9910034065")))))    ; province 99 out of range

(deftest research-and-gs1
  (testing "Bulgaria EGN (weighted mod 11)"
    (is (stdnum/valid? :bg-egn "7523169263"))
    (is (not (stdnum/valid? :bg-egn "7523169264"))))
  (testing "ORCID (ISO 7064 MOD 11-2) - Josiah Carberry"
    (is (stdnum/valid? :orcid "0000-0002-1825-0097"))    ; separators tolerated
    (is (stdnum/valid? :orcid "0000000218250097"))
    (is (not (stdnum/valid? :orcid "0000000218250098"))))
  (testing "ISNI (same MOD 11-2) - real ISNI"
    (is (stdnum/valid? :isni "0000000121032683"))
    (is (not (stdnum/valid? :isni "0000000121032684"))))
  (testing "GTIN-14 (GS1 logistics) - GS1 worked example"
    (is (stdnum/valid? :gtin14 "10614141000415"))
    (is (not (stdnum/valid? :gtin14 "10614141000416"))))
  (testing "SSCC (18-digit GS1 logistics) - GS1 worked example"
    (is (stdnum/valid? :sscc "001234560000000018"))
    (is (not (stdnum/valid? :sscc "001234560000000019"))))
  (testing "GLN (GS1 Global Location Number) - GS1 example"
    (is (stdnum/valid? :gln "0614141000005"))
    (is (not (stdnum/valid? :gln "0614141000006"))))
  (testing "Mexico CURP - government worked example"
    (is (stdnum/valid? :mx-curp "HEGG560427MVZRRL04"))
    (is (not (stdnum/valid? :mx-curp "HEGG560427MVZRRL05")))))

(deftest parse-extraction
  (testing "Mexico CURP extracts birth date, gender, and issuing state"
    (let [p (stdnum/parse :mx-curp "HEGG560427MVZRRL04")]
      (is (= "1956-04-27" (:birth-date p)))
      (is (= :female (:gender p)))
      (is (= "VZ" (:state p)))
      (is (= "Veracruz" (:state-name p)))))
  (testing "Estonia isikukood extracts birth date and gender"
    (let [p (stdnum/parse :ee-ik "37605030299")]
      (is (= "1976-05-03" (:birth-date p)))
      (is (= :male (:gender p)))))
  (testing "JMBG extracts birth date and gender"
    (let [p (stdnum/parse :jmbg "0101006500006")]
      (is (= "2006-01-01" (:birth-date p)))
      (is (= :male (:gender p)))))
  (testing "South Africa ID extracts gender, citizenship, birth date"
    (let [p (stdnum/parse :za-id "8001015009087")]
      (is (= :male (:gender p)))
      (is (true? (:citizen p)))
      (is (= "1980-01-01" (:birth-date p)))))
  (testing "VIN extracts WMI, model year, plant and serial"
    (let [p (stdnum/parse :vin "1HGBH41JXMN109186")]
      (is (= "1HG" (:wmi p)))
      (is (= 1991 (:model-year p)))                       ; char 7 numeric -> pre-2010 cycle
      (is (= "N" (:plant p)))
      (is (= "109186" (:serial p)))))
  (testing "Italy codice fiscale extracts gender, birth day/month/year, comune"
    (let [p (stdnum/parse :it-cf "RSSMRA80A01H501U")]
      (is (= :male (:gender p)))
      (is (= 1 (:birth-day p)))
      (is (= 1 (:birth-month p)))
      (is (= 80 (:birth-year p)))
      (is (= "H501" (:comune-code p))))
    (let [p (stdnum/parse :it-cf "BNCMRA85T41H501W")]      ; female: day encoded as +40
      (is (= :female (:gender p)))
      (is (= 1 (:birth-day p)))
      (is (= 12 (:birth-month p)))))
  (testing "China resident ID extracts full birth date and gender"
    (let [p (stdnum/parse :cn-ric "11010519491231002X")]
      (is (= "1949-12-31" (:birth-date p)))
      (is (= :female (:gender p))))                        ; 17th digit even
    (let [p (stdnum/parse :cn-ric "440524188001010014")]
      (is (= "1880-01-01" (:birth-date p)))
      (is (= :male (:gender p)))))                          ; 17th digit odd
  (testing "Poland PESEL extracts birth date (century from month offset) and gender"
    (let [p (stdnum/parse :pl-pesel "44051401359")]
      (is (= "1944-05-14" (:birth-date p)))
      (is (= :male (:gender p)))))
  (testing "France NIR extracts gender, birth year/month, department"
    (let [p (stdnum/parse :fr-nir "255081416802538")]
      (is (= :female (:gender p)))
      (is (= 55 (:birth-year p)))
      (is (= 8 (:birth-month p)))
      (is (= "14" (:department p)))))
  (testing "Sweden personnummer extracts birth date and gender"
    (let [p (stdnum/parse :se-pnr "811218-9876")]
      (is (= "1981-12-18" (:birth-date p)))
      (is (= :male (:gender p)))))
  (testing "Belgium national number extracts birth date (century from check base) and gender"
    (let [p (stdnum/parse :be-nn "00012511148")]
      (is (= "2000-01-25" (:birth-date p)))
      (is (= :male (:gender p)))))
  (testing "Bulgaria EGN extracts birth date (century from month offset) and gender"
    (let [p (stdnum/parse :bg-egn "7523169263")]
      (is (= "1875-03-16" (:birth-date p)))
      (is (= :female (:gender p)))))
  (testing "parse on an invalid value still returns {:valid? false} with no fields"
    (is (= {:valid? false} (stdnum/parse :mx-curp "HEGG560427MVZRRL05")))
    (is (= {:valid? false} (stdnum/parse :ee-ik "37605030298")))))

(deftest canonical-format
  (testing "ORCID / ISNI group into fours (hyphen vs space, per convention)"
    (is (= "0000-0002-1825-0097" (stdnum/format :orcid "0000000218250097")))
    (is (= "0000 0001 2103 2683" (stdnum/format :isni "0000000121032683"))))
  (testing "CAS registry number reconstructs its hyphenated form"
    (is (= "7732-18-5" (stdnum/format :cas "7732185")))
    (is (= "50-00-0" (stdnum/format :cas "50000"))))
  (testing "LatAm tax IDs reformat to their national display form"
    (is (= "30-70308853-4" (stdnum/format :ar-cuit "30703088534")))
    (is (= "97.004.000-5" (stdnum/format :cl-rut "970040005")))
    (is (= "890.903.938-8" (stdnum/format :co-nit "8909039388"))))
  (testing "personnummer and Belgium NN national display forms"
    (is (= "811218-9876" (stdnum/format :se-pnr "8112189876")))
    (is (= "00.01.25-111.48" (stdnum/format :be-nn "00012511148"))))
  (testing "national IDs reformat to their standard spaced/dotted display forms"
    (is (= "943 476 5919" (stdnum/format :nhs "9434765919")))
    (is (= "AB 12 34 56 C" (stdnum/format :gb-nino "AB123456C")))
    (is (= "AB 12 34 56" (stdnum/format :gb-nino "AB123456")))      ; suffix optional
    (is (= "2341 2341 2346" (stdnum/format :in-aadhaar "234123412346")))
    (is (= "756.9217.0769.85" (stdnum/format :ch-ahv "7569217076985")))
    (is (= "046 454 286" (stdnum/format :ca-sin "046454286")))
    (is (= "2 55 08 14 168 025 38" (stdnum/format :fr-nir "255081416802538"))))
  (testing "format returns nil for invalid input"
    (is (nil? (stdnum/format :ar-cuit "30703088535")))
    (is (nil? (stdnum/format :cas "7732186")))))

(deftest detect-and-unknown
  (testing "detect returns the plausible types for a value"
    (is (some #{:credit-card} (stdnum/detect "4111111111111111")))
    (is (empty? (stdnum/detect "nonsense"))))
  (testing "unknown identifier type throws (caller bug, not bad data)"
    (is (thrown? IllegalArgumentException (stdnum/valid? :not-a-type "x"))))
  (testing "valid? never throws on bad data, only returns false"
    (is (false? (stdnum/valid? :iban "")))
    (is (false? (stdnum/valid? :credit-card "")))))
