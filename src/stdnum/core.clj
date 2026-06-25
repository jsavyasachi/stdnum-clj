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
            LuhnCheckDigit ABANumberCheckDigit CUSIPCheckDigit SedolCheckDigit VerhoeffCheckDigit
            EAN13CheckDigit]
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
(defn- card-parse [^String n]
  {:valid? true :network (network-of n) :iin (subs n 0 6) :last4 (subs n (- (count n) 4))})
(defn- card-format [^String n] (str/join " " (re-seq #".{1,4}" n)))

;; --- IBAN / BIC (iban4j for the rich parse/format, CV for the check) ----------
(def ^:private ^IBANValidator iban-validator (IBANValidator/getInstance))
(defn- iban-valid? [^String n] (.isValid iban-validator n))
(defn- iban-field [f] (try (f) (catch Exception _ nil)))  ; iban4j throws when a BBAN lacks a field
(defn- iban-parse [^String n]
  (let [i (Iban/valueOf n)]
    (cond-> {:valid?    true
             :country   (str (.getCountryCode i))
             :bban      (.getBban i)
             :formatted (.toFormattedString i)}
      (iban-field #(.getBankCode i))      (assoc :bank-code (iban-field #(.getBankCode i)))
      (iban-field #(.getBranchCode i))    (assoc :branch-code (iban-field #(.getBranchCode i)))
      (iban-field #(.getAccountNumber i)) (assoc :account-number (iban-field #(.getAccountNumber i))))))
(defn- iban-format [^String n] (.toFormattedString (Iban/valueOf n)))
(defn- bic-valid? [^String n] (boolean (Bic/valueOf n))) ; throws on invalid; caught upstream
(defn- bic-parse [^String n]
  (let [^Bic b (Bic/valueOf n)
        branch (try (.getBranchCode b) (catch Exception _ nil))]
    (cond-> {:valid?        true
             :bank-code     (.getBankCode b)
             :country       (str (.getCountryCode b))
             :location-code (.getLocationCode b)}
      (seq branch) (assoc :branch-code branch))))

;; --- books / securities -------------------------------------------------------
(def ^:private ^ISBNValidator isbn-validator (ISBNValidator/getInstance))
(def ^:private ^ISSNValidator issn-validator (ISSNValidator/getInstance))
(def ^:private ^ISINValidator isin-validator (ISINValidator/getInstance true))
(defn- isbn-valid? [^String n] (.isValid isbn-validator n))
(defn- issn-hyphenate [^String n] (if (= 8 (count n)) (str (subs n 0 4) "-" (subs n 4)) n))
(defn- issn-valid? [^String n] (.isValid issn-validator (issn-hyphenate n)))
(defn- isin-valid? [^String n] (.isValid isin-validator n))
(defn- isin-parse [^String n] {:valid? true :country (subs n 0 2) :nsin (subs n 2 11)})

;; --- check-digit primitives ---------------------------------------------------
(def ^:private ^LuhnCheckDigit luhn-cd (LuhnCheckDigit.))
(def ^:private ^ABANumberCheckDigit aba-cd (ABANumberCheckDigit.))
(defn- luhn-valid? [^String n] (and (re-matches #"\d+" n) (.isValid luhn-cd n)))
(defn- aba-valid?  [^String n] (and (re-matches #"\d{9}" n) (.isValid aba-cd n)))
(defn- imei-valid? [^String n] (and (re-matches #"\d{15}" n) (.isValid luhn-cd n)))
(defn- imei-parse [^String n] {:valid? true :tac (subs n 0 8) :serial (subs n 8 14)})

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
;; Spain VAT (NIF): a natural-person DNI, a foreigner NIE, or a legal-entity CIF
;; (entity letter + 7 digits + a control that is a digit for some entity types,
;; a letter for others - accept either so no valid number is rejected).
(def ^:private ^String cif-letters "JABCDEFGHI")
(defn- cif? [^String n]
  (and (re-matches #"[ABCDEFGHJNPQRSUVW]\d{7}[0-9A-J]" n)
       (let [total (reduce + (map-indexed
                              (fn [i d] (if (even? i) (let [p (* 2 (long d))] (+ (quot p 10) (mod p 10))) (long d)))
                              (digits-of (subs n 1 8))))
             cd (mod (- 10 (mod (long total) 10)) 10)
             ctrl (.charAt n 8)]
         (or (= ctrl (char (+ 48 cd))) (= ctrl (.charAt cif-letters cd))))))
(defn- es-vat? [^String n]                          ; Spain VAT = DNI, NIE, or CIF
  (let [n (strip-cc n "ES")] (boolean (or (es-dni? n) (es-nie? n) (cif? n)))))

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
(defn- mx-clabe-parse [^String n]                    ; 3 bank + 3 branch + 11 account + 1 check
  {:valid? true :bank-code (subs n 0 3) :branch-code (subs n 3 6) :account (subs n 6 17)})
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

(def ^:private nz-w1 [3 2 7 6 5 4 3 2])
(def ^:private nz-w2 [7 4 3 2 5 2 7 6])
(defn- nz-ird? [^String n]                            ; New Zealand IRD: weighted mod 11, two passes
  (and (re-matches #"\d{8,9}" n)
       (<= 10000000 (Long/parseLong n) 150000000)
       (let [s (if (= 8 (count n)) (str "0" n) n)
             d (digits-of s) base (subvec d 0 8) chk (d 8)
             cd (fn [w] (let [r (mod (long (reduce + (map * base w))) 11)] (if (zero? r) 0 (- 11 r))))
             c1 (cd nz-w1)]
         (if (= c1 10) (let [c2 (cd nz-w2)] (and (not= c2 10) (= c2 chk))) (= c1 chk)))))
(defn- be-nn? [^String n]                             ; Belgium national number: mod 97 (+2 prefix for >=2000)
  (and (re-matches #"\d{11}" n)
       (let [base (subs n 0 9) chk (Integer/parseInt (subs n 9))]
         (or (= chk (- 97 (mod (Long/parseLong base) 97)))
             (= chk (- 97 (mod (Long/parseLong (str "2" base)) 97)))))))
(def ^:private ^String hetu-chk "0123456789ABCDEFHJKLMNPRSTUVWXY")
(defn- fi-hetu? [^String n]                           ; Finland HETU: check char over date+serial mod 31
  (when-let [[_ ddmmyy zzz c] (re-matches #"(\d{6})[-+A-FU-Y]?(\d{3})([0-9A-Y])" n)]
    (= (.charAt hetu-chk (int (mod (Long/parseLong (str ddmmyy zzz)) 31)))
       (.charAt ^String c 0))))

;; FIGI (Financial Instrument Global Identifier, OMG standard): 12 chars - two
;; consonants, 'G', eight consonant/digit chars, and a mod-10 check digit computed
;; over the first 11 (even 1-indexed positions doubled, summing decimal digits).
(defn- figi? [^String n]
  (and (re-matches #"[BCDFGHJKLMNPQRSTVWXYZ]{2}G[BCDFGHJKLMNPQRSTVWXYZ0-9]{8}\d" n)
       (let [s (reduce + (map-indexed
                          (fn [i c] (let [v (code36 c) v (if (odd? i) (* 2 v) v)]
                                      (+ (quot v 10) (mod v 10))))
                          (subs n 0 11)))]
         (= (mod (- 10 (mod (long s) 10)) 10) (- (int (.charAt n 11)) 48)))))

;; more EU VAT (clean-room) ----------------------------------------------------
(defn- mt-vat? [^String n]                            ; Malta: first 6 weighted, 37-complement check
  (let [n (strip-cc n "MT")]
    (and (re-matches #"\d{8}" n)
         (let [d (digits-of n)]
           (= (- 37 (mod (long (reduce + (map * (subvec d 0 6) [3 4 6 7 8 9]))) 37))
              (Integer/parseInt (subs n 6)))))))
(defn- sk-vat? [^String n]                            ; Slovakia: 10 digits, 3rd in set, whole mod 11
  (let [n (strip-cc n "SK")]
    (boolean (and (re-matches #"[1-9]\d{9}" n)
                  (#{\2 \3 \4 \7 \8 \9} (.charAt n 2))
                  (zero? (mod (Long/parseLong n) 11))))))
(defn- lt-vat? [^String n]                            ; Lithuania: 9 or 12 digits, weighted mod 11
  (let [n (strip-cc n "LT")]
    (and (re-matches #"\d{9}|\d{12}" n)
         (let [d (digits-of n) k (dec (count d)) base (subvec d 0 k)
               wsum (fn [start] (reduce + (map-indexed
                                           (fn [i x] (* (long x) (inc (mod (+ i start) 9)))) base)))
               r (mod (long (wsum 0)) 11)
               r (if (= r 10) (mod (long (wsum 2)) 11) r)]
           (= (if (= r 10) 0 r) (d k))))))
(def ^:private cy-vat-map {0 1, 1 0, 2 5, 3 7, 4 9, 5 13, 6 15, 7 17, 8 19, 9 21})
(defn- cy-vat? [^String n]                            ; Cyprus: even-position digits remapped, mod 26 letter
  (let [n (strip-cc n "CY")]
    (and (re-matches #"\d{8}[A-Z]" n)
         (let [d (digits-of (subs n 0 8))
               s (reduce + (map-indexed (fn [i x] (if (even? i) (cy-vat-map x) x)) d))]
           (= (char (+ 65 (mod (long s) 26))) (.charAt n 8))))))
(def ^:private ro-vat-key [7 5 3 2 1 7 5 3 2])
(defn- ro-vat? [^String n]                            ; Romania CUI: key right-aligned, (sum*10 mod 11) mod 10
  (let [n (strip-cc n "RO")]
    (and (re-matches #"\d{2,10}" n)
         (let [d (digits-of n) k (dec (count d))
               s (reduce + (map * (subvec d 0 k) (subvec ro-vat-key (- 9 k))))]
           (= (mod (mod (* (long s) 10) 11) 10) (d k))))))

;; Asia-Pacific business & person identifiers (clean-room from public specs) ---
;; Singapore NRIC/FIN: prefix S/T (citizens, PRs) or F/G (foreigners), 7 digits,
;; weighted mod 11 + prefix offset, check letter from a prefix-class table. The
;; newer M-series FIN uses a different table and is out of scope here.
(def ^:private ^String sg-st-letters "JZIHGFEDCBA")
(def ^:private ^String sg-fg-letters "XWUTRQPNMLK")
(def ^:private sg-weights [2 7 6 5 4 3 2])
(defn- sg-nric? [^String n]
  (when (re-matches #"[STFG]\d{7}[A-Z]" n)
    (let [p (.charAt n 0)
          off (case p \S 0 \T 4 \F 0 \G 4)
          r (mod (+ (long off) (long (reduce + (map * (digits-of (subs n 1 8)) sg-weights)))) 11)]
      (= (.charAt ^String (if (#{\S \T} p) sg-st-letters sg-fg-letters) r) (.charAt n 8)))))

;; Hong Kong HKID: one or two letters (a single letter counts as 36 in the first
;; slot) + 6 digits + a check char (0-9, or A for 10), weighted 9..2 mod 11.
(defn- hk-val ^long [^Character c] (+ 10 (- (int c) 65)))
(defn- hk-id? [^String n]
  (when (re-matches #"[A-Z]{1,2}\d{6}[0-9A]" n)
    (let [^String letters (re-find #"^[A-Z]+" n)
          vals (concat (if (= 1 (count letters))
                         [36 (hk-val (.charAt letters 0))]
                         [(hk-val (.charAt letters 0)) (hk-val (.charAt letters 1))])
                       (digits-of (subs n (count letters) (+ (count letters) 6))))
          c (mod (- 11 (mod (long (reduce + (map * vals [9 8 7 6 5 4 3 2]))) 11)) 11)]
      (= (int (.charAt n (dec (count n)))) (if (= c 10) 65 (+ 48 c))))))

;; South Korea Business Registration Number: 10 digits, weighted with a tens-carry
;; on the 9th digit, mod-10 complement. (The personal RRN is PII and out of scope.)
(def ^:private kr-brn-weights [1 3 7 1 3 7 1 3 5])
(defn- kr-brn? [^String n]
  (and (re-matches #"\d{10}" n)
       (let [d (digits-of n)
             s (+ (long (reduce + (map * (subvec d 0 9) kr-brn-weights)))
                  (quot (* (long (d 8)) 5) 10))]
         (= (mod (- 10 (mod s 10)) 10) (d 9)))))

;; commerce / vehicle / healthcare identifiers (engine-backed + clean-room) ----
(def ^:private ^EAN13CheckDigit ean13-cd (EAN13CheckDigit.))
(defn- ean13? [^String n] (and (re-matches #"\d{13}" n) (.isValid ean13-cd n)))
(defn- upc? [^String n]                               ; UPC-A: a 12-digit GTIN, pad to EAN-13
  (and (re-matches #"\d{12}" n) (.isValid ean13-cd (str "0" n))))

;; VIN (ISO 3779): 17 chars, transliterated, weighted mod 11, check char at pos 9.
(def ^:private vin-tr
  {\A 1 \B 2 \C 3 \D 4 \E 5 \F 6 \G 7 \H 8 \J 1 \K 2 \L 3 \M 4 \N 5
   \P 7 \R 9 \S 2 \T 3 \U 4 \V 5 \W 6 \X 7 \Y 8 \Z 9
   \0 0 \1 1 \2 2 \3 3 \4 4 \5 5 \6 6 \7 7 \8 8 \9 9})
(def ^:private vin-weights [8 7 6 5 4 3 2 10 0 9 8 7 6 5 4 3 2])
(defn- vin? [^String n]
  (and (re-matches #"[A-HJ-NPR-Z0-9]{17}" n)
       (let [s (reduce + (map (fn [c w] (* (long (vin-tr c)) (long w))) n vin-weights))
             r (mod (long s) 11)]
         (= (int (.charAt n 8)) (if (= r 10) 88 (+ 48 r))))))  ; 88 = \X

;; UK NHS number: 10 digits, weighted mod 11 (remainder 10 is invalid).
(defn- nhs? [^String n]
  (and (re-matches #"\d{10}" n)
       (let [d (digits-of n)
             c (- 11 (mod (long (reduce + (map * (subvec d 0 9) [10 9 8 7 6 5 4 3 2]))) 11))
             c (cond (= c 11) 0 (= c 10) -1 :else c)]
         (and (>= c 0) (= c (d 9))))))

;; US NPI: 10 digits beginning 1 or 2, Luhn over the "80840" issuer prefix.
(defn- npi? [^String n]
  (boolean (and (re-matches #"[12]\d{9}" n) (.isValid luhn-cd (str "80840" n)))))

(defn- ean8? [^String n]                              ; EAN-8 / GTIN-8: 3-1 weighted mod 10
  (and (re-matches #"\d{8}" n)
       (let [d (digits-of n)]
         (= (mod (- 10 (mod (long (reduce + (map * (subvec d 0 7) [3 1 3 1 3 1 3]))) 10)) 10) (d 7)))))
(defn- ismn? [^String n]                              ; ISMN: 979-0 prefixed 13-digit EAN
  (and (re-matches #"9790\d{9}" n) (.isValid ean13-cd n)))

;; CAS Registry Number (chemicals): check = sum of digit*(position from right) mod 10.
(defn- cas? [^String n]
  (and (re-matches #"\d{5,10}" n)
       (let [d (digits-of n) k (dec (count d))
             s (reduce + (map-indexed (fn [i x] (* (long x) (inc (long i)))) (reverse (subvec d 0 k))))]
         (= (mod (long s) 10) (d k)))))

;; IMO ship number: "IMO" + 7 digits, weights 7..2 over the first 6, mod 10.
(defn- imo? [^String n]
  (let [n (strip-cc n "IMO")]
    (and (re-matches #"\d{7}" n)
         (let [d (digits-of n)]
           (= (mod (long (reduce + (map * (subvec d 0 6) [7 6 5 4 3 2]))) 10) (d 6))))))

;; more national tax / person identifiers (clean-room) ------------------------
(defn- fr-nir? [^String n]                            ; France NIR: key = 97 - (first 13 mod 97)
  (and (re-matches #"\d{15}" n)
       (= (- 97 (mod (Long/parseLong (subs n 0 13)) 97)) (Integer/parseInt (subs n 13)))))
(defn- pesel? [^String n]                             ; Poland PESEL: weighted mod 10
  (and (re-matches #"\d{11}" n)
       (let [d (digits-of n)]
         (= (mod (- 10 (mod (long (reduce + (map * (subvec d 0 10) [1 3 7 9 1 3 7 9 1 3]))) 10)) 10)
            (d 10)))))
(defn- ar-cuit? [^String n]                           ; Argentina CUIT: weighted mod 11
  (and (re-matches #"\d{11}" n)
       (let [d (digits-of n)
             c (- 11 (mod (long (reduce + (map * (subvec d 0 10) [5 4 3 2 7 6 5 4 3 2]))) 11))]
         (= (cond (= c 11) 0 (= c 10) -1 :else c) (d 10)))))
(defn- cl-rut? [^String n]                            ; Chile RUT: reverse * 2..7 cycle, mod 11 (K=10)
  (when (re-matches #"\d{7,8}[0-9K]" n)
    (let [k (dec (count n))
          s (reduce + (map * (reverse (digits-of (subs n 0 k))) (cycle [2 3 4 5 6 7])))
          c (- 11 (mod (long s) 11))]
      (= (cond (= c 11) 48 (= c 10) 75 :else (+ 48 c)) (int (.charAt n k))))))  ; 48=\0 75=\K
(def ^:private co-nit-weights [3 7 13 17 19 23 29 37 41 43 47 53 59 67 71])
(defn- co-nit? [^String n]                            ; Colombia NIT: weights-from-right, mod 11
  (when (re-matches #"\d{8,16}" n)
    (let [k (dec (count n))
          r (mod (long (reduce + (map * (reverse (digits-of (subs n 0 k))) co-nit-weights))) 11)]
      (= (if (< r 2) r (- 11 r)) (- (int (.charAt n k)) 48)))))
(defn- pe-ruc? [^String n]                            ; Peru RUC: weighted mod 11 (10->0, 11->1)
  (and (re-matches #"\d{11}" n)
       (let [d (digits-of n)
             c (- 11 (mod (long (reduce + (map * (subvec d 0 10) [5 4 3 2 7 6 5 4 3 2]))) 11))]
         (= (cond (= c 11) 1 (= c 10) 0 :else c) (d 10)))))

;; Ireland PPS: 7 digits + check letter + optional 2nd letter (folded in as value*9),
;; check letter from a mod-23 table whose index 0 is 'W'.
(def ^:private ^String ie-pps-letters "WABCDEFGHIJKLMNOPQRSTUV")
(defn- ie-pps? [^String n]
  (when (re-matches #"\d{7}[A-W][A-IW]?" n)
    (let [extra (if (= 9 (count n)) (* 9 (- (int (.charAt n 8)) 64)) 0)
          s (+ (long (reduce + (map * (digits-of (subs n 0 7)) [8 7 6 5 4 3 2]))) (long extra))]
      (= (.charAt ie-pps-letters (int (mod s 23))) (.charAt n 7)))))
(defn- ie-vat? [^String n]                            ; Ireland VAT: 7 digits + mod-23 check letter
  (let [n (strip-cc n "IE")]
    (when (re-matches #"\d{7}[A-W]" n)
      (= (.charAt ie-pps-letters
                  (int (mod (long (reduce + (map * (digits-of (subs n 0 7)) [8 7 6 5 4 3 2]))) 23)))
         (.charAt n 7)))))

;; Estonia isikukood: weighted mod 11, reweighting once when the remainder is 10.
(defn- ee-ik? [^String n]
  (and (re-matches #"\d{11}" n)
       (let [d (digits-of n)
             w (fn [ws] (mod (long (reduce + (map * (subvec d 0 10) ws))) 11))
             r (w [1 2 3 4 5 6 7 8 9 1])
             r (if (= r 10) (w [3 4 5 6 7 8 9 1 2 3]) r)]
         (= (if (= r 10) 0 r) (d 10)))))

;; JMBG: the shared ex-Yugoslav (RS/BA/ME/MK/SI/HR) 13-digit number, weighted mod 11.
(defn- jmbg? [^String n]
  (and (re-matches #"\d{13}" n)
       (let [d (digits-of n)
             m (- 11 (mod (long (reduce + (map * (subvec d 0 12) [7 6 5 4 3 2 7 6 5 4 3 2]))) 11))]
         (= (if (>= m 10) 0 m) (d 12)))))

;; Ecuador cédula: 10 digits, province 01-24, Luhn-like coefficients mod 10.
(defn- ec-ced? [^String n]
  (and (re-matches #"\d{10}" n)
       (<= 1 (Integer/parseInt (subs n 0 2)) 24)
       (let [s (reduce + (map (fn [x c] (let [p (* (long x) (long c))] (if (> p 9) (- p 9) p)))
                              (digits-of (subs n 0 9)) [2 1 2 1 2 1 2 1 2]))]
         (= (mod (- 10 (mod (long s) 10)) 10) (- (int (.charAt n 9)) 48)))))
(defn- bg-egn? [^String n]                            ; Bulgaria EGN: weighted mod 11 (10 -> 0)
  (and (re-matches #"\d{10}" n)
       (let [d (digits-of n)
             r (mod (long (reduce + (map * (subvec d 0 9) [2 4 8 5 10 9 7 3 6]))) 11)]
         (= (if (= r 10) 0 r) (d 9)))))
(defn- nl-vat? [^String n]                            ; Netherlands: 9-digit mod-11 + "B" + 2-digit suffix
  (let [n (strip-cc n "NL")]
    (and (re-matches #"\d{9}B\d{2}" n)
         (not= "00" (subs n 10))
         (let [d (digits-of (subs n 0 9))
               r (mod (long (reduce + (map * (subvec d 0 8) [9 8 7 6 5 4 3 2]))) 11)]
           (and (not= r 10) (= r (d 8)))))))
(defn- lv-vat? [^String n]                            ; Latvia PVN (legal entity, leading digit > 3)
  (let [n (strip-cc n "LV")]
    (and (re-matches #"[4-9]\d{10}" n)
         (let [d (digits-of n)
               r (mod (long (reduce + (map * (subvec d 0 10) [9 1 4 8 3 10 2 5 7 6]))) 11)
               c (cond (= r 4) -1, (> r 4) (+ (- 3 r) 11), :else (- 3 r))]
           (and (not= c -1) (= c (d 10)))))))
(defn- bg-vat? [^String n]                            ; Bulgaria: 9-digit EIK/BULSTAT, or 10-digit EGN
  (let [n (strip-cc n "BG")]
    (cond
      (re-matches #"\d{9}" n)
      (let [d (digits-of n)
            r (mod (long (reduce + (map * (subvec d 0 8) [1 2 3 4 5 6 7 8]))) 11)
            c (if (= r 10)
                (let [r2 (mod (long (reduce + (map * (subvec d 0 8) [3 4 5 6 7 8 9 10]))) 11)]
                  (if (= r2 10) 0 r2))
                r)]
        (= c (d 8)))
      (re-matches #"\d{10}" n) (bg-egn? n)
      :else false)))
(defn- hr-vat? [^String n] (hr-oib? n))               ; Croatia VAT = HR + OIB
(defn- cz-vat? [^String n] (cz-ico? n))               ; Czech VAT (legal entity) = CZ + 8-digit IČO
(defn- pt-vat? [^String n] (pt-nif? n))               ; Portugal VAT = PT + NIF

;; ORCID and ISNI: 16 chars, ISO 7064 MOD 11-2 check (last char may be X). Same
;; algorithm and shape; kept as distinct types for intent.
(defn- mod11-2-code ^long [^String fifteen]           ; -> ASCII code of the check char
  (let [t (reduce (fn [^long acc c] (* (+ acc (- (int c) 48)) 2)) 0 fifteen)
        res (mod (- 12 (mod t 11)) 11)]
    (if (= res 10) 88 (+ 48 res))))                   ; 88 = \X
(defn- orcid? [^String n]
  (and (re-matches #"\d{15}[0-9X]" n) (= (mod11-2-code (subs n 0 15)) (int (.charAt n 15)))))

;; GTIN-14 and SSCC: the longer GS1 keys, same alternating 3-1 mod-10 check.
(defn- gtin-mod10? [^String n]
  (let [d (digits-of n) k (dec (count d))
        s (reduce + (map * (reverse (subvec d 0 k)) (cycle [3 1])))]
    (= (mod (- 10 (mod (long s) 10)) 10) (d k))))
(defn- gtin14? [^String n] (and (re-matches #"\d{14}" n) (gtin-mod10? n)))
(defn- sscc?  [^String n] (and (re-matches #"\d{18}" n) (gtin-mod10? n)))
(defn- gln?   [^String n] (and (re-matches #"\d{13}" n) (.isValid ean13-cd n)))  ; GS1 location number

;; Mexico CURP: 18 chars, weighted base-37 sum (with Ñ in the alphabet), mod-10 check.
(def ^:private curp-val (zipmap "0123456789ABCDEFGHIJKLMNÑOPQRSTUVWXYZ" (range)))
(defn- mx-curp? [^String n]
  (and (re-matches #"[A-Z]{4}\d{6}[HM][A-Z]{5}[0-9A-Z]\d" n)
       (let [s (reduce + (map-indexed (fn [i c] (* (long (curp-val c)) (long (- 18 i)))) (subs n 0 17)))]
         (= (mod (- 10 (mod (long s) 10)) 10) (- (int (.charAt n 17)) 48)))))

;; --- field extraction (parse) for IDs that embed structured data -------------
;; Each runs only after the type's validator has passed, so the input shape is
;; already guaranteed. Birth dates are returned as ISO "YYYY-MM-DD" strings.

(def ^:private curp-states
  {"AS" "Aguascalientes" "BC" "Baja California" "BS" "Baja California Sur" "CC" "Campeche"
   "CL" "Coahuila" "CM" "Colima" "CS" "Chiapas" "CH" "Chihuahua" "DF" "Ciudad de México"
   "DG" "Durango" "GT" "Guanajuato" "GR" "Guerrero" "HG" "Hidalgo" "JC" "Jalisco"
   "MC" "México" "MN" "Michoacán" "MS" "Morelos" "NT" "Nayarit" "NL" "Nuevo León"
   "OC" "Oaxaca" "PL" "Puebla" "QT" "Querétaro" "QR" "Quintana Roo" "SP" "San Luis Potosí"
   "SL" "Sinaloa" "SR" "Sonora" "TC" "Tabasco" "TL" "Tlaxcala" "TS" "Tamaulipas"
   "VZ" "Veracruz" "YN" "Yucatán" "ZS" "Zacatecas" "NE" "Nacido en el Extranjero"})
(defn- curp-parse [^String n]
  (let [yy (subs n 4 6) mm (subs n 6 8) dd (subs n 8 10)
        century (if (Character/isDigit (.charAt n 16)) "19" "20")
        state (subs n 11 13)]
    {:valid?     true
     :birth-date (str century yy "-" mm "-" dd)
     :gender     (if (= \H (.charAt n 10)) :male :female)
     :state      state
     :state-name (curp-states state)}))

(defn- ee-ik-parse [^String n]
  (let [c0 (- (int (.charAt n 0)) 48)
        century (nth ["18" "18" "19" "19" "20" "20" "21" "21"] (dec c0))]
    {:valid?     true
     :birth-date (str century (subs n 1 3) "-" (subs n 3 5) "-" (subs n 5 7))
     :gender     (if (odd? c0) :male :female)}))

(defn- jmbg-parse [^String n]
  (let [yyy (Integer/parseInt (subs n 4 7))
        year (if (>= yyy 900) (+ 1000 yyy) (+ 2000 yyy))
        seq-num (Integer/parseInt (subs n 9 12))]
    {:valid?     true
     :birth-date (str year "-" (subs n 2 4) "-" (subs n 0 2))
     :gender     (if (< seq-num 500) :male :female)
     :region     (subs n 7 9)}))

(defn- za-id-parse [^String n]
  (let [yy (Integer/parseInt (subs n 0 2))
        century (if (<= yy 26) "20" "19")]                ; pivot at the current 2-digit year
    {:valid?     true
     :birth-date (str century (subs n 0 2) "-" (subs n 2 4) "-" (subs n 4 6))
     :gender     (if (>= (Integer/parseInt (subs n 6 10)) 5000) :male :female)
     :citizen    (= \0 (.charAt n 10))}))

;; VIN: WMI (mfr) / VDS / VIS; model year from the position-10 code, disambiguated
;; to the post-2010 cycle when position 7 is a letter (the NHTSA rule).
(def ^:private vin-year-codes
  {\A 1980 \B 1981 \C 1982 \D 1983 \E 1984 \F 1985 \G 1986 \H 1987 \J 1988 \K 1989
   \L 1990 \M 1991 \N 1992 \P 1993 \R 1994 \S 1995 \T 1996 \V 1997 \W 1998 \X 1999 \Y 2000
   \1 2001 \2 2002 \3 2003 \4 2004 \5 2005 \6 2006 \7 2007 \8 2008 \9 2009})
(defn- vin-parse [^String n]
  (let [base (long (vin-year-codes (.charAt n 9)))]
    {:valid?     true
     :wmi        (subs n 0 3)
     :vds        (subs n 3 9)
     :vis        (subs n 9 17)
     :model-year (if (Character/isLetter (.charAt n 6)) (+ base 30) base)
     :plant      (subs n 10 11)
     :serial     (subs n 11 17)}))

;; Poland PESEL: the month field carries the century (01-12=1900s, 21-32=2000s,
;; 41-52=2100s, 61-72=2200s, 81-92=1800s); the 10th digit is the gender.
(def ^:private pesel-centuries [1900 2000 2100 2200 1800])
(defn- pesel-parse [^String n]
  (let [mm (Integer/parseInt (subs n 2 4))
        year (+ (nth pesel-centuries (quot mm 20)) (Integer/parseInt (subs n 0 2)))
        month (inc (mod (dec mm) 20))]
    {:valid?     true
     :birth-date (str year "-" (clojure.core/format "%02d" month) "-" (subs n 4 6))
     :gender     (if (odd? (- (int (.charAt n 9)) 48)) :male :female)}))

;; France NIR: digit 1 = sex, 2-3 = year, 4-5 = month, 6-7 = department. The
;; century is not encoded, so the 2-digit year is returned as-is.
(defn- fr-nir-parse [^String n]
  {:valid?      true
   :gender      (if (= \1 (.charAt n 0)) :male :female)
   :birth-year  (Integer/parseInt (subs n 1 3))
   :birth-month (Integer/parseInt (subs n 3 5))
   :department  (subs n 5 7)})

;; Sweden personnummer: YYMMDD + serial; the 9th digit is the gender. The
;; separator that disambiguates the century is dropped on normalization, so the
;; year is resolved with a pivot at the current 2-digit year.
(defn- se-pnr-parse [^String n]
  (let [yy (Integer/parseInt (subs n 0 2)) century (if (<= yy 26) "20" "19")]
    {:valid?     true
     :birth-date (str century (subs n 0 2) "-" (subs n 2 4) "-" (subs n 4 6))
     :gender     (if (odd? (- (int (.charAt n 8)) 48)) :male :female)}))

;; Non-date embedded info: India PAN holder-type (4th char), Ecuador province
;; (first 2 digits), Peru RUC taxpayer type (first 2 digits).
(def ^:private pan-holder-types
  {\P :individual \C :company \H :huf \F :firm \A :association-of-persons \T :trust
   \B :body-of-individuals \L :local-authority \J :artificial-juridical-person \G :government})
(defn- in-pan-parse [^String n] {:valid? true :holder-type (pan-holder-types (.charAt n 3))})

(def ^:private ec-provinces
  ["Azuay" "Bolívar" "Cañar" "Carchi" "Cotopaxi" "Chimborazo" "El Oro" "Esmeraldas" "Guayas"
   "Imbabura" "Loja" "Los Ríos" "Manabí" "Morona Santiago" "Napo" "Pastaza" "Pichincha"
   "Tungurahua" "Zamora Chinchipe" "Galápagos" "Sucumbíos" "Orellana"
   "Santo Domingo de los Tsáchilas" "Santa Elena"])
(defn- ec-ced-parse [^String n]
  (let [p (Integer/parseInt (subs n 0 2))]
    {:valid? true :province-code p :province (nth ec-provinces (dec p))}))

(defn- pe-ruc-parse [^String n]
  {:valid? true :entity-type (case (subs n 0 2)
                               "20" :company
                               ("10" "15" "17") :natural-person
                               :other)})

;; Belgium national number: YYMMDD + serial; the century is whichever mod-97 base
;; validated (un-prefixed = 1900s, "2"-prefixed = 2000s). Serial parity = gender.
(defn- be-nn-parse [^String n]
  (let [base (subs n 0 9) chk (Integer/parseInt (subs n 9))
        century (if (= chk (- 97 (mod (Long/parseLong base) 97))) "19" "20")]
    {:valid?     true
     :birth-date (str century (subs n 0 2) "-" (subs n 2 4) "-" (subs n 4 6))
     :gender     (if (odd? (Integer/parseInt (subs n 6 9))) :male :female)}))

;; Bulgaria EGN: month offset carries the century (01-12=1900s, 21-32=1800s,
;; 41-52=2000s); the 9th digit is the gender.
(defn- bg-egn-parse [^String n]
  (let [mm (Integer/parseInt (subs n 2 4))
        [century month] (cond (> mm 40) ["20" (- mm 40)] (> mm 20) ["18" (- mm 20)] :else ["19" mm])]
    {:valid?     true
     :birth-date (str century (subs n 0 2) "-" (clojure.core/format "%02d" month) "-" (subs n 4 6))
     :gender     (if (odd? (- (int (.charAt n 8)) 48)) :male :female)}))

;; China resident ID: digits 7-14 are the full YYYYMMDD birth date; the 17th
;; digit is the gender (odd male, even female).
(defn- cn-ric-parse [^String n]
  {:valid?     true
   :birth-date (str (subs n 6 10) "-" (subs n 10 12) "-" (subs n 12 14))
   :gender     (if (odd? (- (int (.charAt n 16)) 48)) :male :female)})

;; Italy codice fiscale: gender + birth day/month + comune. The century is not
;; encoded in a CF, so the 2-digit year is returned as-is (no guessed ISO date).
(def ^:private it-cf-months {\A 1 \B 2 \C 3 \D 4 \E 5 \H 6 \L 7 \M 8 \P 9 \R 10 \S 11 \T 12})
(defn- it-cf-parse [^String n]
  (let [day (Integer/parseInt (subs n 9 11)) female (> day 40)]
    {:valid?      true
     :gender      (if female :female :male)
     :birth-day   (if female (- day 40) day)
     :birth-month (it-cf-months (.charAt n 8))
     :birth-year  (Integer/parseInt (subs n 6 8))
     :comune-code (subs n 11 15)}))

;; --- canonical formatting for IDs with a standard separated display form ------
(defn- group4 [^String sep ^String n] (str/join sep (re-seq #".{4}" n)))
(defn- dotted [^String s]                              ; digits grouped in threes from the right
  (->> (reverse s) (partition-all 3) (map #(apply str (reverse %))) reverse (str/join ".")))
(defn- orcid-format [^String n] (group4 "-" n))
(defn- isni-format  [^String n] (group4 " " n))
(defn- cas-format   [^String n]
  (let [k (count n)] (str (subs n 0 (- k 3)) "-" (subs n (- k 3) (dec k)) "-" (subs n (dec k)))))
(defn- ar-cuit-format [^String n] (str (subs n 0 2) "-" (subs n 2 10) "-" (subs n 10)))
(defn- dash-check-format [^String n]                   ; dotted body + "-" + final check char (CL/CO)
  (let [k (dec (count n))] (str (dotted (subs n 0 k)) "-" (subs n k))))
(defn- se-pnr-format [^String n] (str (subs n 0 6) "-" (subs n 6 10)))
(defn- be-nn-format [^String n]
  (str (subs n 0 2) "." (subs n 2 4) "." (subs n 4 6) "-" (subs n 6 9) "." (subs n 9 11)))
(defn- nhs-format [^String n] (str (subs n 0 3) " " (subs n 3 6) " " (subs n 6 10)))
(defn- nino-format [^String n]
  (str (subs n 0 2) " " (subs n 2 4) " " (subs n 4 6) " " (subs n 6 8)
       (when (> (count n) 8) (str " " (subs n 8)))))
(defn- aadhaar-format [^String n] (str (subs n 0 4) " " (subs n 4 8) " " (subs n 8 12)))
(defn- ch-ahv-format [^String n]
  (str (subs n 0 3) "." (subs n 3 7) "." (subs n 7 11) "." (subs n 11 13)))
(defn- triple3-format [^String n] (str (subs n 0 3) " " (subs n 3 6) " " (subs n 6 9)))  ; SIN/TFN/org
(defn- hk-id-format [^String n]
  (let [k (dec (count n))] (str (subs n 0 k) "(" (subs n k) ")")))
(defn- kr-brn-format [^String n] (str (subs n 0 3) "-" (subs n 3 5) "-" (subs n 5 10)))
(defn- au-abn-format [^String n]
  (str (subs n 0 2) " " (subs n 2 5) " " (subs n 5 8) " " (subs n 8 11)))
(defn- fr-nir-format [^String n]
  (str (subs n 0 1) " " (subs n 1 3) " " (subs n 3 5) " " (subs n 5 7) " "
       (subs n 7 10) " " (subs n 10 13) " " (subs n 13 15)))

(def ^:private registry
  {:credit-card {:validate card-valid? :parse card-parse :format card-format}
   :iban        {:validate iban-valid? :parse iban-parse :format iban-format}
   :bic         {:validate bic-valid? :parse bic-parse}
   :isbn        {:validate isbn-valid?}
   :issn        {:validate issn-valid? :format issn-hyphenate}
   :isin        {:validate isin-valid? :parse isin-parse}
   :aba         {:validate aba-valid?}
   :imei        {:validate imei-valid? :parse imei-parse}
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
   :gb-nino     {:validate nino? :format nino-format}
   :ca-sin      {:validate ca-sin? :format triple3-format}
   :au-abn      {:validate au-abn? :format au-abn-format}
   :in-pan      {:validate in-pan? :parse in-pan-parse}
   :in-aadhaar  {:validate in-aadhaar? :format aadhaar-format}
   :es-dni      {:validate es-dni?}
   :es-nie      {:validate es-nie?}
   :nl-bsn      {:validate nl-bsn?}
   :cn-ric      {:validate cn-ric? :parse cn-ric-parse}
   :se-pnr      {:validate se-pnr? :parse se-pnr-parse :format se-pnr-format}
   :mx-clabe    {:validate mx-clabe? :parse mx-clabe-parse}
   :za-id       {:validate za-id? :parse za-id-parse}
   :no-org      {:validate no-org? :format triple3-format}
   :tr-tc       {:validate tr-tc?}
   :at-vat      {:validate at-vat?}
   :dk-vat      {:validate dk-vat?}
   :fi-vat      {:validate fi-vat?}
   :se-vat      {:validate se-vat?}
   :gr-vat      {:validate gr-vat?}
   :pt-nif      {:validate pt-nif?}
   :cz-ico      {:validate cz-ico?}
   :jp-cn       {:validate jp-cn?}
   :au-tfn      {:validate au-tfn? :format triple3-format}
   :lu-vat      {:validate lu-vat?}
   :si-vat      {:validate si-vat?}
   :in-gstin    {:validate in-gstin?}
   :ee-vat      {:validate ee-vat?}
   :hu-vat      {:validate hu-vat?}
   :hr-oib      {:validate hr-oib?}
   :it-cf       {:validate it-cf? :parse it-cf-parse}
   :ch-uid      {:validate ch-uid?}
   :ch-ahv      {:validate ch-ahv? :format ch-ahv-format}
   :nz-ird      {:validate nz-ird?}
   :be-nn       {:validate be-nn? :parse be-nn-parse :format be-nn-format}
   :fi-hetu     {:validate fi-hetu?}
   :figi        {:validate figi?}
   :mt-vat      {:validate mt-vat?}
   :sk-vat      {:validate sk-vat?}
   :lt-vat      {:validate lt-vat?}
   :cy-vat      {:validate cy-vat?}
   :ro-vat      {:validate ro-vat?}
   :es-vat      {:validate es-vat?}
   :ie-vat      {:validate ie-vat?}
   :nl-vat      {:validate nl-vat?}
   :lv-vat      {:validate lv-vat?}
   :bg-vat      {:validate bg-vat?}
   :hr-vat      {:validate hr-vat?}
   :cz-vat      {:validate cz-vat?}
   :pt-vat      {:validate pt-vat?}
   :sg-nric     {:validate sg-nric?}
   :hk-id       {:validate hk-id? :format hk-id-format}
   :kr-brn      {:validate kr-brn? :format kr-brn-format}
   :ean13       {:validate ean13?}
   :upc         {:validate upc?}
   :vin         {:validate vin? :parse vin-parse}
   :nhs         {:validate nhs? :format nhs-format}
   :npi         {:validate npi?}
   :ean8        {:validate ean8?}
   :ismn        {:validate ismn?}
   :cas         {:validate cas? :format cas-format}
   :imo         {:validate imo?}
   :fr-nir      {:validate fr-nir? :parse fr-nir-parse :format fr-nir-format}
   :pl-pesel    {:validate pesel? :parse pesel-parse}
   :ar-cuit     {:validate ar-cuit? :format ar-cuit-format}
   :cl-rut      {:validate cl-rut? :format dash-check-format}
   :co-nit      {:validate co-nit? :format dash-check-format}
   :pe-ruc      {:validate pe-ruc? :parse pe-ruc-parse}
   :ie-pps      {:validate ie-pps?}
   :ee-ik       {:validate ee-ik? :parse ee-ik-parse}
   :jmbg        {:validate jmbg? :parse jmbg-parse}
   :ec-ced      {:validate ec-ced? :parse ec-ced-parse}
   :bg-egn      {:validate bg-egn? :parse bg-egn-parse}
   :orcid       {:validate orcid? :format orcid-format}
   :isni        {:validate orcid? :format isni-format}
   :gtin14      {:validate gtin14?}
   :sscc        {:validate sscc?}
   :gln         {:validate gln?}
   :mx-curp     {:validate mx-curp? :parse curp-parse}})

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
