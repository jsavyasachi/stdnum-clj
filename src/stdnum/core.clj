(ns stdnum.core
  "Unified validation, parsing, and formatting of standard identifier numbers.

  One API over several identifier types - credit cards, IBAN/BIC, ISBN, ISSN,
  ISIN, US bank routing (ABA), IMEI, and the raw Luhn check - dispatched on a
  type keyword. Idiomatic Clojure data in and out; an idiomatic facade over the
  maintained Apache Commons Validator and iban4j engines (it does not reinvent
  the algorithms).

      (valid?  :iban \"GB82 WEST 1234 5698 7654 32\")  ;=> true
      (parse   :credit-card \"4111111111111111\")      ;=> {:valid? true :network :visa}
      (format  :iban \"GB82WEST12345698765432\")       ;=> \"GB82 WEST 1234 5698 7654 32\"
      (detect  \"4111111111111111\")                   ;=> [:credit-card :luhn]

  `valid?`/`parse`/`format` throw only on an unknown identifier type (a caller
  bug); bad input data never throws - `valid?` returns false, `parse` returns
  {:valid? false}, `format` returns nil."
  (:refer-clojure :exclude [format])
  (:require [clojure.string :as str])
  (:import [org.apache.commons.validator.routines
            CreditCardValidator IBANValidator ISBNValidator ISSNValidator ISINValidator]
           [org.apache.commons.validator.routines.checkdigit
            LuhnCheckDigit ABANumberCheckDigit CUSIPCheckDigit SedolCheckDigit VerhoeffCheckDigit]
           [org.iban4j Iban Bic]))

(defn- norm ^String [s]
  (if s (-> (str s) (str/replace #"[\s.\-/]" "") str/upper-case) ""))

;; --- credit cards -------------------------------------------------------------
;; The default CreditCardValidator omits Diners, so build one spanning every
;; network we report; per-network validators drive detection.
(def ^:private card-flags
  [[:visa CreditCardValidator/VISA] [:mastercard CreditCardValidator/MASTERCARD]
   [:amex CreditCardValidator/AMEX] [:discover CreditCardValidator/DISCOVER]
   [:diners CreditCardValidator/DINERS]])

(def ^:private ^CreditCardValidator all-cards
  (CreditCardValidator. (long (reduce bit-or 0 (map second card-flags)))))

(def ^:private card-validators
  (mapv (fn [[k flag]] [k (CreditCardValidator. (long flag))]) card-flags))

(defn- network-of [^String n]
  (some (fn [[k ^CreditCardValidator v]] (when (.isValid v n) k)) card-validators))

(defn- card-valid? [^String n] (.isValid all-cards n))
(defn- card-parse [^String n] {:valid? true :network (network-of n)})
(defn- card-format [^String n] (str/join " " (re-seq #".{1,4}" n)))

;; --- IBAN / BIC (iban4j for the rich parse/format, CV for the check) ----------
(def ^:private ^IBANValidator iban-validator (IBANValidator/getInstance))
(defn- iban-valid? [^String n] (.isValid iban-validator n))
(defn- iban-parse [^String n]
  (let [i (Iban/valueOf n)]
    {:valid?    true
     :country   (str (.getCountryCode i))
     :bban      (.getBban i)
     :formatted (.toFormattedString i)}))
(defn- iban-format [^String n] (.toFormattedString (Iban/valueOf n)))
(defn- bic-valid? [^String n] (boolean (Bic/valueOf n))) ; throws on invalid; caught upstream

;; --- books / securities -------------------------------------------------------
(def ^:private ^ISBNValidator isbn-validator (ISBNValidator/getInstance))
(def ^:private ^ISSNValidator issn-validator (ISSNValidator/getInstance))
(def ^:private ^ISINValidator isin-validator (ISINValidator/getInstance true))
(defn- isbn-valid? [^String n] (.isValid isbn-validator n))
(defn- issn-hyphenate [^String n] (if (= 8 (count n)) (str (subs n 0 4) "-" (subs n 4)) n))
(defn- issn-valid? [^String n] (.isValid issn-validator (issn-hyphenate n)))
(defn- isin-valid? [^String n] (.isValid isin-validator n))

;; --- check-digit primitives ---------------------------------------------------
(def ^:private ^LuhnCheckDigit luhn-cd (LuhnCheckDigit.))
(def ^:private ^ABANumberCheckDigit aba-cd (ABANumberCheckDigit.))
(defn- luhn-valid? [^String n] (and (re-matches #"\d+" n) (.isValid luhn-cd n)))
(defn- aba-valid?  [^String n] (and (re-matches #"\d{9}" n) (.isValid aba-cd n)))
(defn- imei-valid? [^String n] (and (re-matches #"\d{15}" n) (.isValid luhn-cd n)))

;; --- global / national entity & person identifiers (clean-room from public specs) ---

;; LEI (ISO 17442): 20 chars [A-Z0-9], ISO 7064 MOD 97-10 over the whole string
;; (letters A-Z -> 10-35), valid when the running remainder is 1.
(defn- lei-mod97 ^long [^String s]
  (reduce (fn [^long acc c]
            (let [d (if (Character/isDigit ^char c) (- (int c) 48) (+ 10 (- (int c) 65)))]
              (mod (+ (* acc (if (>= d 10) 100 10)) d) 97)))
          0 s))
(defn- lei-valid? [^String n] (and (re-matches #"[A-Z0-9]{20}" n) (= 1 (lei-mod97 n))))

;; Brazilian CPF / CNPJ: weighted mod-11 dual check digits; sequences of one
;; repeated digit pass the arithmetic but are not valid documents, so reject them.
(defn- check-digit ^long [digits weights]
  (let [r (mod (long (reduce + (map * digits weights))) 11)] (if (< r 2) 0 (- 11 r))))
(defn- digits-of [^String n] (mapv #(- (int %) 48) n))
(defn- repdigit? [^String n] (apply = (seq n)))

(defn- cpf-valid? [^String n]
  (and (re-matches #"\d{11}" n) (not (repdigit? n))
       (let [d (digits-of n)]
         (and (= (check-digit (subvec d 0 9) (range 10 1 -1)) (d 9))
              (= (check-digit (subvec d 0 10) (range 11 1 -1)) (d 10))))))
(defn- cpf-format [^String n]
  (str (subs n 0 3) "." (subs n 3 6) "." (subs n 6 9) "-" (subs n 9)))

(def ^:private cnpj-w1 [5 4 3 2 9 8 7 6 5 4 3 2])
(def ^:private cnpj-w2 [6 5 4 3 2 9 8 7 6 5 4 3 2])
(defn- cnpj-valid? [^String n]
  (and (re-matches #"\d{14}" n) (not (repdigit? n))
       (let [d (digits-of n)]
         (and (= (check-digit (subvec d 0 12) cnpj-w1) (d 12))
              (= (check-digit (subvec d 0 13) cnpj-w2) (d 13))))))
(defn- cnpj-format [^String n]
  (str (subs n 0 2) "." (subs n 2 5) "." (subs n 5 8) "/" (subs n 8 12) "-" (subs n 12)))

;; securities identifiers (engine-backed check digits) ------------------------
(def ^:private ^CUSIPCheckDigit cusip-cd (CUSIPCheckDigit.))
(def ^:private ^SedolCheckDigit sedol-cd (SedolCheckDigit.))
(defn- cusip-valid? [^String n] (and (re-matches #"[0-9A-Z]{9}" n) (.isValid cusip-cd n)))
(defn- sedol-valid? [^String n] (and (re-matches #"[0-9A-Z]{7}" n) (.isValid sedol-cd n)))

;; US national numbers (clean-room from the public structural rules) ----------
;; SSN: AAA-GG-SSSS. Area not 000/666/900-999; group not 00; serial not 0000;
;; plus the SSA's reserved advertising/promo numbers.
(defn- ssn-valid? [^String n]
  (boolean
   (and (re-matches #"\d{9}" n)
        (let [a (subs n 0 3)]
          (and (not (#{"000" "666"} a))
               (not= \9 (.charAt a 0))
               (not= "00" (subs n 3 5))
               (not= "0000" (subs n 5 9))
               (not= "078051120" n)
               (not (re-matches #"98765432\d" n)))))))
(defn- ssn-format [^String n] (str (subs n 0 3) "-" (subs n 3 5) "-" (subs n 5 9)))

;; EIN: NN-NNNNNNN, valid when the two-digit prefix is an IRS campus code.
(def ^:private ein-prefixes
  #{"01" "02" "03" "04" "05" "06" "10" "11" "12" "13" "14" "15" "16" "20" "21" "22" "23" "24" "25"
    "26" "27" "30" "31" "32" "33" "34" "35" "36" "37" "38" "39" "40" "41" "42" "43" "44" "45" "46"
    "47" "48" "50" "51" "52" "53" "54" "55" "56" "57" "58" "59" "60" "61" "62" "63" "64" "65" "66"
    "67" "68" "71" "72" "73" "74" "75" "76" "77" "80" "81" "82" "83" "84" "85" "86" "87" "88" "90"
    "91" "92" "93" "94" "95" "98" "99"})
(defn- ein-valid? [^String n] (and (re-matches #"\d{9}" n) (contains? ein-prefixes (subs n 0 2))))
(defn- ein-format [^String n] (str (subs n 0 2) "-" (subs n 2 9)))

;; EU / UK VAT numbers + UK NINO (clean-room from the public specs). VAT numbers
;; carry an optional leading ISO country code; strip it before checking digits.
(defn- strip-cc ^String [^String n ^String cc]
  (if (str/starts-with? n cc) (subs n (count cc)) n))

(defn- de-vat? [^String n]                         ; ISO 7064 MOD 11,10 over 9 digits
  (let [n (strip-cc n "DE")]
    (and (re-matches #"\d{9}" n)
         (let [d (digits-of n)
               p (reduce (fn [p dig]
                           (let [m (mod (+ (long dig) (long p)) 10) m (if (zero? m) 10 m)]
                             (mod (* m 2) 11)))
                         10 (subvec d 0 8))]
           (= (mod (- 11 (long p)) 10) (d 8))))))

(defn- fr-vat? [^String n]                          ; 2-digit key over a Luhn-valid SIREN
  (let [n (strip-cc n "FR")]
    (and (re-matches #"\d{11}" n)
         (let [k (Integer/parseInt (subs n 0 2)) siren (subs n 2)]
           (and (.isValid luhn-cd siren)
                (= k (mod (+ 12 (* 3 (mod (Long/parseLong siren) 97))) 97)))))))

(defn- it-vat? [^String n]                          ; Partita IVA: 11-digit Luhn
  (let [n (strip-cc n "IT")] (boolean (and (re-matches #"\d{11}" n) (.isValid luhn-cd n)))))

(defn- be-vat? [^String n]                          ; 97 - (first 8 mod 97) == last 2
  (let [n (strip-cc n "BE")]
    (and (re-matches #"[01]\d{9}" n)
         (= (- 97 (mod (Long/parseLong (subs n 0 8)) 97)) (Integer/parseInt (subs n 8))))))

(defn- pl-vat? [^String n]                          ; NIP: weighted mod 11
  (let [n (strip-cc n "PL")]
    (and (re-matches #"\d{10}" n)
         (let [d (digits-of n)
               r (mod (long (reduce + (map * (subvec d 0 9) [6 5 7 2 3 4 5 6 7]))) 11)]
           (and (not= r 10) (= r (d 9)))))))

(defn- gb-vat? [^String n]                          ; weighted mod 97 (old and 9755 variants)
  (let [n (strip-cc n "GB")]
    (and (re-matches #"\d{9}" n)
         (let [d (digits-of n)
               ws (long (reduce + (map * (subvec d 0 7) [8 7 6 5 4 3 2])))
               t (+ ws (Integer/parseInt (subs n 7)))]
           (or (zero? (mod t 97)) (zero? (mod (+ t 55) 97)))))))

(def ^:private nino-bad-first #{"D" "F" "I" "Q" "U" "V"})
(def ^:private nino-bad-second #{"D" "F" "I" "O" "Q" "U" "V"})
(def ^:private nino-bad-prefix #{"BG" "GB" "NK" "KN" "TN" "NT" "ZZ"})
(defn- nino? [^String n]                            ; UK National Insurance Number (structural)
  (boolean
   (and (re-matches #"[A-Z]{2}\d{6}[A-D]?" n)
        (not (nino-bad-first (subs n 0 1)))
        (not (nino-bad-second (subs n 1 2)))
        (not (nino-bad-prefix (subs n 0 2))))))

;; other national identifiers (clean-room / engine-backed) --------------------
(def ^:private ^VerhoeffCheckDigit verhoeff-cd (VerhoeffCheckDigit.))
(defn- ca-sin? [^String n]                          ; Canada SIN: 9-digit Luhn
  (boolean (and (re-matches #"\d{9}" n) (.isValid luhn-cd n))))
(def ^:private abn-weights [10 1 3 5 7 9 11 13 15 17 19])
(defn- au-abn? [^String n]                          ; Australia ABN: weighted mod 89 (first digit -1)
  (and (re-matches #"\d{11}" n)
       (let [d (digits-of n) d (assoc d 0 (dec (long (d 0))))]
         (zero? (mod (long (reduce + (map * d abn-weights))) 89)))))
(def ^:private pan-entity #{\P \C \H \F \A \T \B \L \J \G})
(defn- in-pan? [^String n]                          ; India PAN: structural, 4th char = entity type
  (boolean (and (re-matches #"[A-Z]{5}[0-9]{4}[A-Z]" n) (pan-entity (.charAt n 3)))))
(defn- in-aadhaar? [^String n]                      ; India Aadhaar: 12 digits, Verhoeff, leads 2-9
  (boolean (and (re-matches #"[2-9]\d{11}" n) (.isValid verhoeff-cd n))))

(def ^:private ^String dni-letters "TRWAGMYFPDXBNJZSQVHLCKE")
(defn- es-dni? [^String n]                          ; Spain DNI: 8 digits + mod-23 control letter
  (boolean (and (re-matches #"\d{8}[A-Z]" n)
                (= (.charAt dni-letters (int (mod (Long/parseLong (subs n 0 8)) 23)))
                   (.charAt n 8)))))
(defn- es-nie? [^String n]                          ; Spain NIE: X/Y/Z + 7 digits + control letter
  (boolean (and (re-matches #"[XYZ]\d{7}[A-Z]" n)
                (let [p (case (.charAt n 0) \X "0" \Y "1" \Z "2")
                      v (Long/parseLong (str p (subs n 1 8)))]
                  (= (.charAt dni-letters (int (mod v 23))) (.charAt n 8))))))
(def ^:private bsn-weights [9 8 7 6 5 4 3 2 -1])
(defn- nl-bsn? [^String n]                           ; Netherlands BSN: 11-test (elfproef)
  (and (re-matches #"\d{8,9}" n)
       (let [s (if (= 8 (count n)) (str "0" n) n)]
         (and (zero? (mod (long (reduce + (map * (digits-of s) bsn-weights))) 11))
              (not (zero? (Long/parseLong s)))))))
(def ^:private cn-weights [7 9 10 5 8 4 2 1 6 3 7 9 10 5 8 4 2])
(def ^:private ^String cn-check "10X98765432")
(defn- cn-ric? [^String n]                           ; China resident ID: ISO 7064 MOD 11-2
  (boolean (and (re-matches #"\d{17}[0-9X]" n)
                (= (.charAt cn-check (int (mod (long (reduce + (map * (digits-of (subs n 0 17)) cn-weights))) 11)))
                   (.charAt n 17)))))
(defn- se-pnr? [^String n]                           ; Sweden personnummer: 10-digit Luhn
  (boolean (and (re-matches #"\d{10}" n) (.isValid luhn-cd n))))

(def ^:private clabe-weights (vec (take 17 (cycle [3 7 1]))))
(defn- mx-clabe? [^String n]                         ; Mexico CLABE bank account: weighted mod 10
  (and (re-matches #"\d{18}" n)
       (let [d (digits-of n)
             s (reduce + (map (fn [x w] (mod (* (long x) (long w)) 10)) (subvec d 0 17) clabe-weights))]
         (= (mod (- 10 (mod (long s) 10)) 10) (d 17)))))
(defn- za-id? [^String n]                            ; South Africa ID: 13-digit Luhn
  (boolean (and (re-matches #"\d{13}" n) (.isValid luhn-cd n))))
(def ^:private no-org-weights [3 2 7 6 5 4 3 2])
(defn- no-org? [^String n]                           ; Norway organisasjonsnummer: mod 11
  (and (re-matches #"\d{9}" n)
       (let [d (digits-of n)
             c (- 11 (mod (long (reduce + (map * (subvec d 0 8) no-org-weights))) 11))]
         (cond (= c 11) (zero? (long (d 8))) (= c 10) false :else (= c (d 8))))))
(defn- tr-tc? [^String n]                            ; Turkey TC Kimlik No: two check digits
  (and (re-matches #"[1-9]\d{10}" n)
       (let [d (digits-of n)
             odd (+ (d 0) (d 2) (d 4) (d 6) (d 8))
             even (+ (d 1) (d 3) (d 5) (d 7))]
         (and (= (mod (- (* (long odd) 7) (long even)) 10) (d 9))
              (= (mod (long (reduce + (subvec d 0 10))) 10) (d 10))))))

;; more EU VAT (clean-room) ----------------------------------------------------
(defn- at-vat? [^String n]                           ; Austria ATU: cross-sum, check = (96-sum) mod 10
  (let [n (strip-cc (strip-cc n "ATU") "AT")]
    (and (re-matches #"\d{8}" n)
         (let [d (digits-of n)
               s (reduce + (map (fn [x w] (let [p (* (long x) (long w))] (+ (quot p 10) (mod p 10))))
                                (subvec d 0 7) [1 2 1 2 1 2 1]))]
           (= (mod (- 96 (long s)) 10) (d 7))))))
(defn- dk-vat? [^String n]                           ; Denmark: weighted sum mod 11 == 0
  (let [n (strip-cc n "DK")]
    (and (re-matches #"\d{8}" n)
         (zero? (mod (long (reduce + (map * (digits-of n) [2 7 6 5 4 3 2 1]))) 11)))))
(defn- fi-vat? [^String n]                           ; Finland: weighted mod 11
  (let [n (strip-cc n "FI")]
    (and (re-matches #"\d{8}" n)
         (let [d (digits-of n) r (mod (long (reduce + (map * (subvec d 0 7) [7 9 10 5 8 4 2]))) 11)]
           (cond (= r 0) (zero? (long (d 7))) (= r 1) false :else (= (- 11 r) (d 7)))))))
(defn- se-vat? [^String n]                           ; Sweden: 10-digit Luhn org number + "01"
  (let [n (strip-cc n "SE")]
    (boolean (and (re-matches #"\d{12}" n) (= "01" (subs n 10)) (.isValid luhn-cd (subs n 0 10))))))
(defn- gr-vat? [^String n]                            ; Greece AFM: powers-of-two weights, mod 11 mod 10
  (let [n (strip-cc (strip-cc n "EL") "GR")]
    (and (re-matches #"\d{9}" n)
         (let [d (digits-of n)]
           (= (mod (mod (long (reduce + (map * (subvec d 0 8) [256 128 64 32 16 8 4 2]))) 11) 10)
              (d 8))))))

;; more national / company identifiers (clean-room) ---------------------------
(defn- pt-nif? [^String n]                            ; Portugal NIF: weighted mod 11
  (let [n (strip-cc n "PT")]
    (and (re-matches #"\d{9}" n)
         (let [d (digits-of n)
               c (- 11 (mod (long (reduce + (map * (subvec d 0 8) [9 8 7 6 5 4 3 2]))) 11))]
           (= (if (>= c 10) 0 c) (d 8))))))
(defn- cz-ico? [^String n]                            ; Czech IČO: weighted mod 11
  (let [n (strip-cc n "CZ")]
    (and (re-matches #"\d{8}" n)
         (let [d (digits-of n)
               r (mod (long (reduce + (map * (subvec d 0 7) [8 7 6 5 4 3 2]))) 11)]
           (= (mod (- 11 r) 10) (d 7))))))
(defn- jp-cn? [^String n]                             ; Japan corporate number: leading check digit
  (and (re-matches #"\d{13}" n)
       (let [d (vec (reverse (digits-of (subs n 1))))
             s (reduce + (map-indexed (fn [i x] (* (long x) (if (even? i) 1 2))) d))]
         (= (- 9 (mod (long s) 9)) (- (int (.charAt n 0)) 48)))))
(defn- au-tfn? [^String n]                            ; Australia Tax File Number: weighted mod 11
  (and (re-matches #"\d{9}" n)
       (zero? (mod (long (reduce + (map * (digits-of n) [1 4 3 7 5 8 6 9 10]))) 11))))
(defn- lu-vat? [^String n]                            ; Luxembourg VAT: first 6 mod 89 == last 2
  (let [n (strip-cc n "LU")]
    (and (re-matches #"\d{8}" n)
         (= (mod (Long/parseLong (subs n 0 6)) 89) (Integer/parseInt (subs n 6))))))
(defn- si-vat? [^String n]                            ; Slovenia VAT: weighted mod 11
  (let [n (strip-cc n "SI")]
    (and (re-matches #"\d{8}" n)
         (let [d (digits-of n)
               c (- 11 (mod (long (reduce + (map * (subvec d 0 7) [8 7 6 5 4 3 2]))) 11))]
           (cond (= c 10) false (= c 11) (zero? (long (d 7))) :else (= c (d 7)))))))
(defn- code36 ^long [^Character c]                    ; 0-9 -> 0-9, A-Z -> 10-35
  (if (Character/isDigit c) (- (int c) 48) (+ 10 (- (int c) 65))))
(defn- ee-vat? [^String n]                            ; Estonia VAT: weighted mod 10
  (let [n (strip-cc n "EE")]
    (and (re-matches #"\d{9}" n)
         (let [d (digits-of n)]
           (= (mod (- 10 (mod (long (reduce + (map * (subvec d 0 8) [3 7 1 3 7 1 3 7]))) 10)) 10) (d 8))))))
(defn- hu-vat? [^String n]                            ; Hungary VAT: weighted mod 10
  (let [n (strip-cc n "HU")]
    (and (re-matches #"\d{8}" n)
         (let [d (digits-of n)]
           (= (mod (- 10 (mod (long (reduce + (map * (subvec d 0 7) [9 7 3 1 9 7 3]))) 10)) 10) (d 7))))))
(defn- hr-oib? [^String n]                            ; Croatia OIB: ISO 7064 MOD 11,10
  (let [n (strip-cc n "HR")]
    (and (re-matches #"\d{11}" n)
         (let [d (digits-of n)
               a (reduce (fn [a dig]
                           (let [a (mod (+ (long a) (long dig)) 10) a (if (zero? a) 10 a)] (mod (* a 2) 11)))
                         10 (subvec d 0 10))]
           (= (mod (- 11 (long a)) 10) (d 10))))))
(defn- in-gstin? [^String n]                          ; India GSTIN: base-36 mod-36 check char
  (and (re-matches #"\d{2}[A-Z]{5}\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]" n)
       (let [s (reduce + (map-indexed (fn [i c] (let [p (* (code36 c) (long (if (odd? i) 2 1)))]
                                                  (+ (quot p 36) (mod p 36))))
                                      (subs n 0 14)))
             chk (mod (- 36 (mod (long s) 36)) 36)
             chkc (if (< chk 10) (char (+ 48 chk)) (char (+ 65 (- chk 10))))]
         (= chkc (.charAt n 14)))))

;; Italy codice fiscale: 16 chars, position-dependent value tables, mod-26 check letter
(def ^:private it-cf-odd
  [1 0 5 7 9 13 15 17 19 21 1 0 5 7 9 13 15 17 19 21 2 4 18 20 11 3 6 8 12 14 16 10 22 25 24 23])
(defn- it-cf-even ^long [^Character c]
  (if (Character/isDigit c) (- (int c) 48) (- (int c) 65)))
(defn- it-cf? [^String n]
  (and (re-matches #"[A-Z0-9]{16}" n)
       (let [s (reduce + (map-indexed (fn [i c] (if (even? i) (nth it-cf-odd (int (code36 c))) (it-cf-even c)))
                                      (subs n 0 15)))]
         (= (char (+ 65 (mod (long s) 26))) (.charAt n 15)))))

;; Switzerland UID (business id) + AHV (social security)
(defn- ch-uid? [^String n]                            ; CHE + 9 digits, weighted mod 11
  (let [n (strip-cc n "CHE")]
    (and (re-matches #"\d{9}" n)
         (let [d (digits-of n)
               c (- 11 (mod (long (reduce + (map * (subvec d 0 8) [5 4 3 2 7 6 5 4]))) 11))]
           (cond (= c 10) false (= c 11) (zero? (long (d 8))) :else (= c (d 8)))))))
(defn- ch-ahv? [^String n]                            ; 756 + 10 digits, EAN-13 check
  (and (re-matches #"756\d{10}" n)
       (let [d (digits-of n)
             s (reduce + (map-indexed (fn [i x] (* (long x) (long (if (even? i) 1 3)))) (subvec d 0 12)))]
         (= (mod (- 10 (mod (long s) 10)) 10) (d 12)))))

(def ^:private registry
  {:credit-card {:validate card-valid? :parse card-parse :format card-format}
   :iban        {:validate iban-valid? :parse iban-parse :format iban-format}
   :bic         {:validate bic-valid?}
   :isbn        {:validate isbn-valid?}
   :issn        {:validate issn-valid? :format issn-hyphenate}
   :isin        {:validate isin-valid?}
   :aba         {:validate aba-valid?}
   :imei        {:validate imei-valid?}
   :luhn        {:validate luhn-valid?}
   :lei         {:validate lei-valid?}
   :cusip       {:validate cusip-valid?}
   :sedol       {:validate sedol-valid?}
   :br-cpf      {:validate cpf-valid? :format cpf-format}
   :br-cnpj     {:validate cnpj-valid? :format cnpj-format}
   :us-ssn      {:validate ssn-valid? :format ssn-format}
   :us-ein      {:validate ein-valid? :format ein-format}
   :de-vat      {:validate de-vat?}
   :fr-vat      {:validate fr-vat?}
   :it-vat      {:validate it-vat?}
   :be-vat      {:validate be-vat?}
   :pl-vat      {:validate pl-vat?}
   :gb-vat      {:validate gb-vat?}
   :gb-nino     {:validate nino?}
   :ca-sin      {:validate ca-sin?}
   :au-abn      {:validate au-abn?}
   :in-pan      {:validate in-pan?}
   :in-aadhaar  {:validate in-aadhaar?}
   :es-dni      {:validate es-dni?}
   :es-nie      {:validate es-nie?}
   :nl-bsn      {:validate nl-bsn?}
   :cn-ric      {:validate cn-ric?}
   :se-pnr      {:validate se-pnr?}
   :mx-clabe    {:validate mx-clabe?}
   :za-id       {:validate za-id?}
   :no-org      {:validate no-org?}
   :tr-tc       {:validate tr-tc?}
   :at-vat      {:validate at-vat?}
   :dk-vat      {:validate dk-vat?}
   :fi-vat      {:validate fi-vat?}
   :se-vat      {:validate se-vat?}
   :gr-vat      {:validate gr-vat?}
   :pt-nif      {:validate pt-nif?}
   :cz-ico      {:validate cz-ico?}
   :jp-cn       {:validate jp-cn?}
   :au-tfn      {:validate au-tfn?}
   :lu-vat      {:validate lu-vat?}
   :si-vat      {:validate si-vat?}
   :in-gstin    {:validate in-gstin?}
   :ee-vat      {:validate ee-vat?}
   :hu-vat      {:validate hu-vat?}
   :hr-oib      {:validate hr-oib?}
   :it-cf       {:validate it-cf?}
   :ch-uid      {:validate ch-uid?}
   :ch-ahv      {:validate ch-ahv?}})

(def types
  "The set of identifier-type keywords this library understands."
  (set (keys registry)))

(defn- entry ^clojure.lang.IPersistentMap [type]
  (or (registry type)
      (throw (IllegalArgumentException.
              (str "Unknown identifier type: " (pr-str type)
                   ". Known types: " (sort types))))))

(defn compact
  "Return `s` stripped of spaces, hyphens, and dots and upper-cased - the
  canonical compact form shared by every identifier type."
  [s]
  (norm s))

(defn valid?
  "True if `s` is a valid identifier of `type`. Bad data returns false; an
  unknown `type` throws IllegalArgumentException."
  [type s]
  (let [{:keys [validate]} (entry type)]
    (try (boolean (validate (norm s))) (catch Exception _ false))))

(defn parse
  "Validate `s` as `type` and return a map. On success: at least `{:valid? true}`,
  plus type-specific fields (e.g. card `:network`; IBAN `:country`/`:bban`/
  `:formatted`). On bad data: `{:valid? false}`. Unknown `type` throws."
  [type s]
  (let [{:keys [validate parse]} (entry type)
        n (norm s)]
    (if (try (boolean (validate n)) (catch Exception _ false))
      (if parse (try (parse n) (catch Exception _ {:valid? true})) {:valid? true})
      {:valid? false})))

(defn format
  "Canonical human-readable form of `s` as `type` (e.g. IBAN grouped in fours,
  ISSN hyphenated, card grouped in fours), or nil if `s` is not valid. Unknown
  `type` throws."
  [type s]
  (let [{:keys [validate format]} (entry type)
        n (norm s)]
    (when (try (boolean (validate n)) (catch Exception _ false))
      (if format (format n) n))))

(defn detect
  "Return a vector of the identifier types that consider `s` valid (possibly
  several, e.g. a card number is also Luhn-valid). Empty when nothing matches."
  [s]
  (let [n (norm s)]
    (vec (for [[type {:keys [validate]}] registry
               :when (try (boolean (validate n)) (catch Exception _ false))]
           type))))

(defn card-network
  "The card network of `s` (`:visa` `:mastercard` `:amex` `:discover` `:diners`),
  or nil if `s` is not a recognized card number."
  [s]
  (network-of (norm s)))
