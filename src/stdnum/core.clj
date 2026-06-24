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
   :se-pnr      {:validate se-pnr?}})

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
