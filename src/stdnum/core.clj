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
           [org.iban4j Iban Bic]
           [java.math BigInteger]
           [java.security MessageDigest]
           [java.util Arrays]))

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

;; --- network / mobile identifiers -------------------------------------------
(defn- mac-compact ^String [^String n] (str/replace n #":" ""))
(defn- mac-valid? [^String n] (boolean (re-matches #"[0-9A-F]{12}" (mac-compact n))))
(defn- mac-parse [^String n]
  (let [n (mac-compact n)
        first-octet (Integer/parseInt (subs n 0 2) 16)]
    {:valid? true
     :oui (subs n 0 6)
     :locally-administered? (bit-test first-octet 1)
     :multicast? (bit-test first-octet 0)}))
(defn- mac-format [^String n] (str/join ":" (re-seq #".{2}" (mac-compact n))))

(defn- imsi-valid? [^String n]
  (and (re-matches #"\d{6,15}" n)
       (let [mcc (subs n 0 3)] (or (= "001" mcc) (not= \0 (.charAt ^String mcc 0))))))
(defn- imsi-parse [^String n] {:valid? true :mcc (subs n 0 3)})

(defn- hex-val ^long [^Character c] (Character/digit (char c) 16))
(defn- meid-check ^long [^String n]
  (let [[total _] (reduce (fn [[^long total double?] c]
                            (let [v (hex-val c)
                                  p (if double? (* v 2) v)]
                              [(+ total (quot p 16) (mod p 16)) (not double?)]))
                          [0 true] (reverse n))]
    (mod (- 16 (mod (long total) 16)) 16)))
(defn- meid-valid? [^String n]
  (and (re-matches #"[0-9A-F]{14}[0-9A-F]?" n)
       (or (= 14 (count n)) (= (meid-check (subs n 0 14)) (hex-val (.charAt n 14))))))
(defn- meid-parse [^String n]
  {:valid? true :regional-code (subs n 0 2) :manufacturer (subs n 2 8) :serial (subs n 8 14)})

;; --- cryptocurrency addresses ------------------------------------------------
(def ^:private ^String bitcoin-base58 "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")
(def ^:private base58-values (zipmap bitcoin-base58 (range)))
(def ^:private ^BigInteger b58 (BigInteger/valueOf 58))

(defn- sha256 ^bytes [^bytes bs]
  (.digest (MessageDigest/getInstance "SHA-256") bs))

(defn- base58-decode ^bytes [^String s]
  (when (seq s)
    (let [leading (count (take-while #(= \1 %) s))
          decoded (reduce (fn [^BigInteger acc c]
                            (if-let [v (base58-values c)]
                              (.add (.multiply acc b58) (BigInteger/valueOf (long v)))
                              (reduced nil)))
                          BigInteger/ZERO s)]
      (when decoded
        (let [raw (.toByteArray ^BigInteger decoded)
              raw-len (alength raw)
              start (if (and (> raw-len 1) (zero? (aget raw 0))) 1 0)
              body-len (if (zero? (.signum ^BigInteger decoded)) 0 (- raw-len start))
              out (byte-array (+ leading body-len))]
          (when (pos? body-len)
            (System/arraycopy raw start out leading body-len))
          out)))))

(defn- bitcoin-base58-parse [^String n]
  (when-let [bs (base58-decode n)]
    (when (= 25 (alength bs))
      (let [payload (Arrays/copyOfRange bs 0 21)
            checksum (Arrays/copyOfRange bs 21 25)
            digest (sha256 (sha256 payload))
            expected (Arrays/copyOfRange digest 0 4)
            version (bit-and 0xff (aget bs 0))]
        (when (Arrays/equals checksum expected)
          (case version
            0 {:valid? true :encoding :base58check :type :p2pkh}
            5 {:valid? true :encoding :base58check :type :p2sh}
            nil))))))

(def ^:private ^String bech32-charset "qpzry9x8gf2tvdw0s3jn54khce6mua7l")
(def ^:private bech32-values (zipmap bech32-charset (range)))
(def ^:private bech32-gen [0x3b6a57b2 0x26508e6d 0x1ea119fa 0x3d4233dd 0x2a1462b3])

(defn- bech32-polymod ^long [values]
  (reduce (fn [^long chk ^long v]
            (let [top (bit-shift-right chk 25)
                  chk (bit-xor (bit-shift-left (bit-and chk 0x1ffffff) 5) v)]
              (reduce (fn [^long c i]
                        (if (pos? (bit-and (bit-shift-right top i) 1))
                          (bit-xor c (long (nth bech32-gen i)))
                          c))
                      chk (range 5))))
          1 values))

(defn- bech32-parse [^String n]
  (let [lower (str/lower-case n)
        upper (str/upper-case n)]
    (when (and (or (= n lower) (= n upper)) (str/starts-with? lower "bc1"))
      (let [sep (.lastIndexOf lower "1")]
        (when (and (pos? sep) (<= (+ sep 7) (count lower)))
          (let [hrp (subs lower 0 sep)
                data (subs lower (inc sep))
                values (reduce (fn [acc c]
                                 (if-let [v (bech32-values c)]
                                   (conj acc v)
                                   (reduced nil)))
                               [] data)]
            (when (and (= "bc" hrp) values
                       (= 1 (bech32-polymod
                             (concat (map #(bit-shift-right (int %) 5) hrp)
                                     [0]
                                     (map #(bit-and (int %) 31) hrp)
                                     values))))
              {:valid? true :encoding :bech32 :type :segwit})))))))

(defn- bitcoin-parse [^String n] (or (bitcoin-base58-parse n) (bech32-parse n)))
(defn- bitcoin-valid? [^String n] (boolean (bitcoin-parse n)))

;; --- ISO structural identifiers ---------------------------------------------
(defn- isrc-valid? [^String n] (boolean (re-matches #"[A-Z]{2}[A-Z0-9]{3}\d{7}" n)))
(defn- isrc-parse [^String n]
  {:valid? true :country (subs n 0 2) :registrant (subs n 2 5) :year (subs n 5 7) :designation (subs n 7 12)})
(defn- isrc-format [^String n] (str (subs n 0 2) "-" (subs n 2 5) "-" (subs n 5 7) "-" (subs n 7 12)))

(defn- isil-valid? [^String n]
  (boolean (and (<= (count n) 16) (re-matches #"[A-Za-z0-9]{1,4}-[A-Za-z0-9/:-]+" n))))
(defn- isil-parse [^String n] {:valid? true :prefix (subs n 0 (.indexOf n "-"))})

(def ^:private cfi-categories
  {\E :equity \D :debt \C :collective-investment \R :entitlement \O :listed-option
   \F :future \S :swap \H :non-listed-option \I :spot \J :forward \K :strategy
   \L :financing \T :referential \M :other})
(defn- cfi-valid? [^String n] (boolean (and (re-matches #"[A-Z]{6}" n) (cfi-categories (.charAt n 0)))))
(defn- cfi-parse [^String n] {:valid? true :category (cfi-categories (.charAt n 0))})

(defn- eic-value ^long [^Character c]
  (cond
    (Character/isDigit c) (- (int c) 48)
    (= \- c) 36
    :else (+ 10 (- (int c) 65))))
(defn- eu-eic-valid? [^String n]
  (and (re-matches #"\d{2}[A-Z][A-Z0-9-]{12}[A-Z0-9]" n)
       (let [s (reduce + (map (fn [c w] (* (eic-value c) (long w))) (subs n 0 15) (range 16 1 -1)))
             check (- 36 (mod (dec (long s)) 37))
             expected (if (< check 10) (+ 48 check) (+ 65 (- check 10)))]
         (and (not= 36 check)
              (= expected (int (.charAt n 15)))))))
(defn- eu-eic-parse [^String n] {:valid? true :office (subs n 0 2) :object-type (subs n 2 3)})

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
(defn- de-wkn? [^String n] (boolean (re-matches #"[0-9A-HJ-NP-Z]{6}" n)))

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
(defn- taxpayer-parse [^String n] {:valid? true :area (subs n 0 3) :group (subs n 3 5) :serial (subs n 5 9)})

(defn- itin-group? [^String g]
  (let [n (Integer/parseInt g)]
    (or (<= 50 n 65) (<= 70 n 88) (<= 90 n 92) (<= 94 n 99))))
(defn- itin-valid? [^String n]
  (and (re-matches #"9\d{8}" n) (itin-group? (subs n 3 5))))
(defn- atin-valid? [^String n]
  (and (re-matches #"9\d{2}93\d{4}" n)))

(defn- ptin-valid? [^String n] (boolean (re-matches #"P\d{8}" n)))

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

(defn- mod11-10-check ^long [^String base]
  (let [p (reduce (fn [^long p dig]
                    (let [s (mod (+ (long dig) p) 10)
                          s (if (zero? s) 10 s)]
                      (mod (* 2 s) 11)))
                  10 (digits-of base))]
    (mod (- 11 p) 10)))

(defn- de-idnr-repeat-rule? [^String n]
  (let [freqs (vals (frequencies (subs n 0 10)))
        repeats (filter #(> (long %) 1) freqs)]
    (and (= 1 (count repeats))
         (<= 2 (long (first repeats)) 3)
         (every? #(<= (long %) 3) freqs))))
(defn- de-idnr? [^String n]
  (and (re-matches #"[1-9]\d{10}" n)
       (de-idnr-repeat-rule? n)
       (= (mod11-10-check (subs n 0 10)) (- (int (.charAt n 10)) 48))))

(defn- fr-vat? [^String n]                          ; 2-digit key over a Luhn-valid SIREN
  (let [n (strip-cc n "FR")]
    (and (re-matches #"\d{11}" n)
         (let [k (Integer/parseInt (subs n 0 2)) siren (subs n 2)]
           (and (.isValid luhn-cd siren)
                (= k (mod (+ 12 (* 3 (mod (Long/parseLong siren) 97))) 97)))))))
(defn- mc-tva? [^String n]                          ; Monaco TVA: French TVA key over 000xxxxxx
  (let [n (strip-cc n "FR")]
    (and (re-matches #"\d{11}" n)
         (= "000" (subs n 2 5))
         (= (Integer/parseInt (subs n 0 2))
            (mod (+ 12 (* 3 (mod (Long/parseLong (subs n 2)) 97))) 97)))))

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

(def ^:private utr-weights [6 7 8 9 10 5 4 3 2])
(def ^:private utr-check-table [2 1 9 8 7 6 5 4 3 2 1])
(defn- gb-utr-body ^String [^String n]
  (if (and (= 11 (count n)) (= \K (.charAt n 10))) (subs n 0 10) n))
(defn- gb-utr? [^String n]
  (let [n (gb-utr-body n)]
    (and (re-matches #"\d{10}" n)
         (let [d (digits-of n)
               r (mod (long (reduce + (map * (subvec d 1 10) utr-weights))) 11)]
           (= (nth utr-check-table r) (d 0))))))
(def ^:private ^String upn-alphabet "ABCDEFGHJKLMNPQRTUVWXYZ0123456789")
(def ^:private upn-la-numbers
  #{201 202 203 204 205 206 207 208 209 210 211 212 213 301 302 303 304
    305 306 307 308 309 310 311 312 313 314 315 316 317 318 319 320 330
    331 332 333 334 335 336 340 341 342 343 344 350 351 352 353 354 355
    356 357 358 359 370 371 372 373 380 381 382 383 384 390 391 392 393
    394 420 800 801 802 803 805 806 807 808 810 811 812 813 815 816 821
    822 823 825 826 830 831 835 836 837 840 841 845 846 850 851 852 855
    856 857 860 861 865 866 867 868 869 870 871 872 873 874 876 877 878
    879 880 881 882 883 884 885 886 887 888 889 890 891 892 893 894 895
    896 908 909 916 919 921 925 926 928 929 931 933 935 936 937 938})
(defn- gb-upn? [^String n]                            ; UK Unique Pupil Number
  (and (re-matches #"[A-Z]\d{11}[A-Z0-9]" n)
       (contains? upn-la-numbers (Integer/parseInt (subs n 1 4)))
       (let [check (mod (long (reduce + (map-indexed
                                          (fn [i ch] (* (+ 2 (long i))
                                                        (.indexOf upn-alphabet (int ch))))
                                          (subs n 1 13))))
                        23)]
         (= (.charAt upn-alphabet (int check)) (.charAt n 0)))))

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
(defn- nl-elfproef-checksum ^long [^String n]
  (mod (long (reduce + (map * (digits-of n) bsn-weights))) 11))
(defn- nl-bsn? [^String n]                           ; Netherlands BSN: 11-test (elfproef)
  (and (re-matches #"\d{8,9}" n)
       (let [s (if (= 8 (count n)) (str "0" n) n)]
         (and (zero? (nl-elfproef-checksum s)) (not (zero? (Long/parseLong s)))))))
(defn- nl-brin? [^String n]                           ; Netherlands BRIN: 2 digits + 2 letters + optional 2-digit location
  (boolean (re-matches #"\d{2}[A-Z]{2}(\d{2})?" n)))
(defn- nl-identiteitskaartnummer? [^String n]          ; Netherlands passport/ID-card number: structural, no O
  (boolean (and (re-matches #"[A-Z]{2}[0-9A-Z]{6}\d" n) (not (str/includes? n "O")))))
(defn- nl-onderwijsnummer? [^String n]                 ; Netherlands education number: BSN-style 11-test, checksum remainder 5
  (and (re-matches #"\d{9}" n)
       (str/starts-with? n "10")
       (not (zero? (Long/parseLong n)))
       (= 5 (nl-elfproef-checksum n))))
(def ^:private nl-postcode-blacklist #{"SA" "SD" "SS"})
(defn- nl-postcode? [^String n]                        ; Netherlands postal code: 4 digits + 2 letters, banned letter pairs
  (let [n (strip-cc n "NL")]
    (boolean (when-let [[_ suffix] (re-matches #"[1-9]\d{3}([A-Z]{2})" n)]
               (not (nl-postcode-blacklist suffix))))))
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
(def ^:private cbu-bank-weights [7 1 3 9 7 1 3])
(def ^:private cbu-account-weights [3 9 7 1 3 9 7 1 3 9 7 1 3])
(defn- weighted-mod10-check ^long [digits weights]
  (mod (- 10 (mod (long (reduce + (map * digits weights))) 10)) 10))
(defn- ar-cbu? [^String n]
  (and (re-matches #"\d{22}" n)
       (let [d (digits-of n)]
         (and (= (weighted-mod10-check (subvec d 0 7) cbu-bank-weights) (d 7))
              (= (weighted-mod10-check (subvec d 8 21) cbu-account-weights) (d 21))))))
(defn- ar-cbu-parse [^String n]
  {:valid? true :bank (subs n 0 3) :branch (subs n 3 7) :account (subs n 8 21)})
(def ^:private ccc-weights [1 2 4 8 5 10 9 7 3 6])
(defn- ccc-check ^long [^String payload]
  (let [r (mod (long (reduce + (map * (digits-of payload) ccc-weights))) 11)
        d (- 11 r)]
    (cond (= d 11) 0 (= d 10) 1 :else d)))
(defn- es-ccc? [^String n]
  (and (re-matches #"\d{20}" n)
       (= (ccc-check (str "00" (subs n 0 8))) (- (int (.charAt n 8)) 48))
       (= (ccc-check (subs n 10 20)) (- (int (.charAt n 9)) 48))))
(defn- es-ccc-parse [^String n]
  {:valid? true :bank (subs n 0 4) :branch (subs n 4 8) :account (subs n 10 20)})
(defn- za-id? [^String n]                            ; South Africa ID: 13-digit Luhn
  (boolean (and (re-matches #"\d{13}" n) (.isValid luhn-cd n))))
(def ^:private no-org-weights [3 2 7 6 5 4 3 2])
(defn- no-org? [^String n]                           ; Norway organisasjonsnummer: mod 11
  (and (re-matches #"\d{9}" n)
       (let [d (digits-of n)
             c (- 11 (mod (long (reduce + (map * (subvec d 0 8) no-org-weights))) 11))]
         (cond (= c 11) (zero? (long (d 8))) (= c 10) false :else (= c (d 8))))))
(defn- no-mva? [^String n]
  (let [n (strip-cc n "NO")]
    (and (re-matches #"\d{9}MVA" n) (no-org? (subs n 0 9)))))
(def ^:private no-kontonr-weights [6 7 8 9 4 5 6 7 8 9])
(defn- no-kontonr? [^String n]                        ; Norway bank account: 11-digit mod-11, 7-digit postgiro Luhn
  (and (re-matches #"\d+" n)
       (case (count n)
         7 (.isValid luhn-cd n)
         11 (= (mod (long (reduce + (map * (digits-of (subs n 0 10)) no-kontonr-weights))) 11)
               (- (int (.charAt n 10)) 48))
         false)))
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
(defn- dk-cvr? [^String n]                            ; Denmark CVR: VAT algorithm, no leading zero
  (and (re-matches #"[1-9]\d{7}" n)
       (zero? (mod (long (reduce + (map * (digits-of n) [2 7 6 5 4 3 2 1]))) 11))))
(defn- fi-vat? [^String n]                           ; Finland: weighted mod 11
  (let [n (strip-cc n "FI")]
    (and (re-matches #"\d{8}" n)
         (let [d (digits-of n) r (mod (long (reduce + (map * (subvec d 0 7) [7 9 10 5 8 4 2]))) 11)]
           (cond (= r 0) (zero? (long (d 7))) (= r 1) false :else (= (- 11 r) (d 7)))))))
(defn- fi-ytunnus? [^String n]                        ; Finland Business ID: same check as FI VAT, hyphenated display
  (and (re-matches #"\d{8}" n)
       (let [d (digits-of n)
             r (mod (long (reduce + (map * (subvec d 0 7) [7 9 10 5 8 4 2]))) 11)]
         (cond (= r 0) (zero? (long (d 7))) (= r 1) false :else (= (- 11 r) (d 7))))))
(def ^:private fi-association-low-numbers
  #{1 6 7 9 12 14 15 16 18 22 23 24 27 28 29 35 36 38 40 41 42 43 45 46
    50 52 55 58 60 64 65 68 72 75 76 77 78 83 84 85 89 92})
(defn- fi-associationid? [^String n]                  ; Finland association id: 1-6 digits, registered low numbers only
  (and (re-matches #"\d{1,6}" n)
       (or (>= (count n) 3) (fi-association-low-numbers (Integer/parseInt n)))))
(defn- fi-veronumero? [^String n]                     ; Finland individual tax number: 12 digits, no check digit
  (boolean (re-matches #"\d{12}" n)))
(defn- fo-vn? [^String n]                             ; Faroe Islands V-number: optional FO prefix + 6 digits
  (boolean (re-matches #"\d{6}" (strip-cc n "FO"))))
(defn- se-vat? [^String n]                           ; Sweden: 10-digit Luhn org number + "01"
  (let [n (strip-cc n "SE")]
    (boolean (and (re-matches #"\d{12}" n) (= "01" (subs n 10)) (.isValid luhn-cd (subs n 0 10))))))
(defn- se-postnummer? [^String n]                     ; Sweden postal code: optional SE prefix + nonzero 5 digits
  (let [n (strip-cc n "SE")]
    (boolean (and (re-matches #"\d{5}" n) (not (str/starts-with? n "0"))))))
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
(defn- ch-vat? [^String n]                            ; Swiss VAT = UID + MWST/TVA/IVA/TPV suffix
  (and (re-matches #"CHE\d{9}(MWST|TVA|IVA|TPV)" n)
       (ch-uid? (subs n 0 12))))
(defn- ch-ahv? [^String n]                            ; 756 + 10 digits, EAN-13 check
  (and (re-matches #"756\d{10}" n)
       (let [d (digits-of n)
             s (reduce + (map-indexed (fn [i x] (* (long x) (long (if (even? i) 1 3)))) (subvec d 0 12)))]
         (= (mod (- 10 (mod (long s) 10)) 10) (d 12)))))
(def ^:private esr-table [0 9 4 6 8 2 7 1 3 5])
(defn- ch-esr? [^String n]                            ; Swiss ESR/QRR reference: 27 digits, modulo 10 recursive.
  (and (re-matches #"\d{27}" n)
       (let [d (digits-of n)
             carry (reduce (fn [^long carry digit] (long (nth esr-table (mod (+ carry (long digit)) 10))))
                           0 (subvec d 0 26))]
         (= (mod (- 10 carry) 10) (d 26)))))

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
(defn- be-bis? [^String n]                            ; Belgium BIS: national-number checksum, month +20 or +40
  (and (be-nn? n)
       (let [m (Integer/parseInt (subs n 2 4))]
         (or (<= 20 m 32) (<= 40 m 52)))))
(defn- be-ssn? [^String n] (or (be-bis? n) (be-nn? n)))
(defn- be-eid? [^String n]                            ; Belgian eID card: first 10 digits mod 97, 0 -> 97
  (and (re-matches #"\d{12}" n)
       (pos? (Long/parseLong n))
       (let [r (mod (Long/parseLong (subs n 0 10)) 97)
             c (if (zero? r) 97 r)]
         (= c (Integer/parseInt (subs n 10))))))
(defn- be-ogm-compact ^String [^String n] (str/replace n #"\+" ""))
(defn- be-ogm? [^String n]                            ; Belgian OGM/VCS: 10 digits + mod-97 check, 0 -> 97.
  (let [n (be-ogm-compact n)]
    (and (re-matches #"\d{12}" n)
         (let [r (mod (Long/parseLong (subs n 0 10)) 97)
               c (if (zero? r) 97 r)]
           (= c (Integer/parseInt (subs n 10)))))))
(defn- be-ogm-parse [^String n]
  {:valid? true :number (subs (be-ogm-compact n) 0 10)})
(def ^:private ^String hetu-chk "0123456789ABCDEFHJKLMNPRSTUVWXY")
(defn- fi-hetu? [^String n]                           ; Finland HETU: check char over date+serial mod 31
  (when-let [[_ ddmmyy zzz c] (re-matches #"(\d{6})[-+A-FU-Y]?(\d{3})([0-9A-Y])" n)]
    (= (.charAt hetu-chk (int (mod (Long/parseLong (str ddmmyy zzz)) 31)))
       (.charAt ^String c 0))))
(defn- is-vsk? [^String n]                            ; Iceland VAT: optional IS prefix + 5 or 6 digits
  (let [n (strip-cc n "IS")]
    (boolean (re-matches #"\d{5,6}" n))))

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
(def ^:private sg-uen-other-entity-types
  #{"CC" "CD" "CH" "CL" "CM" "CP" "CS" "CX" "DP" "FB" "FC" "FM" "FN"
    "GA" "GB" "GS" "HS" "LL" "LP" "MB" "MC" "MD" "MH" "MM" "MQ" "NB"
    "NR" "PA" "PB" "PF" "RF" "RP" "SM" "SS" "TC" "TU" "VH" "XL"})
(def ^:private sg-uen-business-weights [10 4 9 3 8 2 7 1])
(def ^:private sg-uen-local-company-weights [10 8 6 4 9 7 5 3 1])
(def ^:private sg-uen-other-weights [4 3 5 3 10 2 2 5 7])
(def ^:private ^String sg-uen-business-letters "XMKECAWLJDB")
(def ^:private ^String sg-uen-local-company-letters "ZKCMDNERGWH")
(def ^:private ^String sg-uen-other-alphabet "ABCDEFGHJKLMNPQRSTUVWX0123456789")
(defn- sg-uen? [^String n]
  (let [year (str (.getYear (java.time.LocalDate/now)))
        yy (subs year 2)]
    (cond
      (re-matches #"\d{8}[A-Z]" n)
      (let [r (mod (long (reduce + (map * (digits-of (subs n 0 8)) sg-uen-business-weights))) 11)]
        (= (.charAt sg-uen-business-letters (int r)) (.charAt n 8)))

      (re-matches #"\d{9}[A-Z]" n)
      (and (not (pos? (compare (subs n 0 4) year)))
           (let [r (mod (long (reduce + (map * (digits-of (subs n 0 9)) sg-uen-local-company-weights))) 11)]
             (= (.charAt sg-uen-local-company-letters (int r)) (.charAt n 9))))

      (re-matches #"[RST]\d{2}[A-Z]{2}\d{4}[A-Z]" n)
      (and (or (not= \T (.charAt n 0)) (not (pos? (compare (subs n 1 3) yy))))
           (contains? sg-uen-other-entity-types (subs n 3 5))
           (let [s (reduce + (map (fn [ch w] (* (long (.indexOf sg-uen-other-alphabet (int ch))) (long w)))
                                  (subs n 0 9) sg-uen-other-weights))
                 r (mod (- (long s) 5) 11)]
             (= (.charAt sg-uen-other-alphabet (int r)) (.charAt n 9))))

      :else false)))

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
(defn- cr-cpf? [^String n]                           ; Costa Rica physical person ID: 0P + tomo + asiento
  (boolean (re-matches #"0\d{9}" n)))
(def ^:private cr-cpj-class-three-types
  #{"002" "003" "004" "005" "006" "007" "008" "009" "010" "011" "012" "013"
    "014" "101" "102" "103" "104" "105" "106" "107" "108" "109" "110"})
(defn- cr-cpj? [^String n]                           ; Costa Rica legal person ID: structural class/type rules
  (and (re-matches #"\d{10}" n)
       (case (.charAt n 0)
         \2 (#{"100" "200" "300" "400"} (subs n 1 4))
         \3 (contains? cr-cpj-class-three-types (subs n 1 4))
         \4 (= "000" (subs n 1 4))
         \5 (= "001" (subs n 1 4))
         false)))
(defn- cr-cr? [^String n]                            ; Costa Rica resident/DIMEX ID: structural
  (boolean (re-matches #"1\d{10,11}" n)))

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

(defn- valid-date? [^long year ^long month ^long day]
  (try (java.time.LocalDate/of year month day) true (catch Exception _ false)))

;; JMBG: the shared ex-Yugoslav (RS/BA/ME/MK/SI/HR) 13-digit number, weighted mod 11.
(defn- jmbg? [^String n]
  (and (re-matches #"\d{13}" n)
       (let [d (digits-of n)
             m (- 11 (mod (long (reduce + (map * (subvec d 0 12) [7 6 5 4 3 2 7 6 5 4 3 2]))) 11))]
         (= (if (>= m 10) 0 m) (d 12)))))

(defn- si-emso? [^String n]                           ; Slovenia EMŠO: JMBG checksum + date
  (and (jmbg? n)
       (let [year (Integer/parseInt (subs n 4 7))
             year (+ year (if (< year 800) 2000 1000))]
         (valid-date? year (Integer/parseInt (subs n 2 4)) (Integer/parseInt (subs n 0 2))))))

(defn- no-fodselsnummer? [^String n]                  ; Norway birth number: date + two mod-11 checks
  (and (re-matches #"\d{11}" n)
       (let [d (digits-of n)
             cd (fn [digits weights]
                  (let [c (- 11 (mod (long (reduce + (map * digits weights))) 11))]
                    (cond (= c 11) 0 (= c 10) nil :else c)))
             c1 (cd (subvec d 0 9) [3 7 6 1 8 9 4 5 2])
             c2 (when c1 (cd (subvec d 0 10) [5 4 3 2 7 6 5 4 3 2]))]
         (and (= c1 (d 9))
              (= c2 (d 10))
              (let [raw-day (Integer/parseInt (subs n 0 2))
                    day (cond-> raw-day (> raw-day 40) (- 40))
                    raw-month (Integer/parseInt (subs n 2 4))
                    month (cond-> raw-month (> raw-month 40) (- 40))
                    yy (Integer/parseInt (subs n 4 6))
                    individ (Integer/parseInt (subs n 6 9))
                    year (cond
                           (< individ 500) (+ 1900 yy)
                           (and (< individ 750) (>= yy 54)) (+ 1800 yy)
                           (and (< individ 1000) (< yy 40)) (+ 2000 yy)
                           (and (<= 900 individ) (< individ 1000) (>= yy 40)) (+ 1900 yy)
                           :else nil)]
                (and (< raw-day 80)
                     year
                     (valid-date? year month day)
                     (let [^java.time.LocalDate birth-date
                           (java.time.LocalDate/of (long year) (long month) (long day))]
                       (not (.isAfter birth-date (java.time.LocalDate/now))))))))))

(def ^:private ro-cnp-counties
  #{"01" "02" "03" "04" "05" "06" "07" "08" "09" "10" "11" "12" "13" "14"
    "15" "16" "17" "18" "19" "20" "21" "22" "23" "24" "25" "26" "27" "28"
    "29" "30" "31" "32" "33" "34" "35" "36" "37" "38" "39" "40" "41" "42"
    "43" "44" "45" "46" "47" "48" "51" "52" "70" "80" "81" "82" "83"})
(defn- ro-cnp? [^String n]                           ; Romania CNP: weighted mod 11 (10 -> 1)
  (and (re-matches #"\d{13}" n)
       (not= \0 (.charAt n 0))
       (contains? ro-cnp-counties (subs n 7 9))
       (let [d (digits-of n)
             century (case (.charAt n 0) (\3 \4) 1800 (\5 \6) 2000 1900)
             year (+ century (Integer/parseInt (subs n 1 3)))
             month (Integer/parseInt (subs n 3 5))
             day (Integer/parseInt (subs n 5 7))
             r (mod (long (reduce + (map * (subvec d 0 12) [2 7 9 1 4 6 3 5 8 2 7 9]))) 11)]
         (and (valid-date? year month day)
              (= (if (= r 10) 1 r) (d 12))))))

(defn- ro-cui? [^String n]                            ; Romania CUI/CIF: same checksum as RO VAT
  (let [n (strip-cc n "RO")]
    (and (re-matches #"[1-9]\d{1,9}" n)
         (ro-vat? n))))
(defn- ro-cf? [^String n]                             ; Romania CF: CUI/CIF, or a 13-digit CNP
  (let [c (strip-cc n "RO")]
    (if (= 13 (count c))
      (ro-cnp? c)
      (ro-cui? n))))
(def ^:private ro-onrc-counties
  (set (concat (range 1 41) [51 52])))
(defn- ro-onrc-old? [^String n]
  (when-let [[_ county serial year] (re-matches #"[JFC](\d{2})(\d{1,5})(\d{4})" n)]
    (let [county (Integer/parseInt county)
          year (Integer/parseInt year)]
      (and (contains? ro-onrc-counties county)
           (<= 1990 year 2024)))))
(defn- ro-onrc-check ^long [^String n]
  (let [letter-digit (mod (int (.charAt n 0)) 10)
        digits (digits-of (str letter-digit (subs n 1 (dec (count n)))))]
    (mod (long (reduce + digits)) 10)))
(defn- ro-onrc-new? [^String n]
  (and (re-matches #"[JFC]\d{13}" n)
       (let [year (Integer/parseInt (subs n 1 5))
             county (Integer/parseInt (subs n 11 13))
             this-year (.getYear (java.time.LocalDate/now))]
         (and (<= 1990 year this-year)
              (cond
                (< year 2024) (contains? ro-onrc-counties county)
                (= year 2024) (or (zero? county) (contains? ro-onrc-counties county))
                :else (zero? county))
              (= (ro-onrc-check n) (- (int (.charAt n 13)) 48))))))
(defn- ro-onrc? [^String n]
  (and (re-matches #"[JFC].+" n)
       (or (ro-onrc-new? n) (ro-onrc-old? n))))

(defn- cz-rc? [^String n]                            ; Czech/Slovak RČ: date plus 10-digit mod-11 check
  (and (re-matches #"\d{9,10}" n)
       (let [yy (Integer/parseInt (subs n 0 2))
             year (+ 1900 yy)
             month (mod (mod (Integer/parseInt (subs n 2 4)) 50) 20)
             day (Integer/parseInt (subs n 4 6))
             year (cond
                    (and (= 9 (count n)) (>= year 1980)) (- year 100)
                    (and (= 10 (count n)) (< year 1954)) (+ year 100)
                    :else year)]
         (and (if (= 9 (count n)) (<= year 1953) true)
              (valid-date? year month day)
              (or (= 9 (count n))
                  (= (mod (mod (Long/parseLong (subs n 0 9)) 11) 10)
                     (- (int (.charAt n 9)) 48)))))))
(defn- sk-rc? [^String n] (cz-rc? n))

(defn- kr-rrn? [^String n]                           ; South Korea RRN: birth date, place code, weighted mod 11
  (and (re-matches #"\d{13}" n)
       (let [d (digits-of n)
             year (+ (Integer/parseInt (subs n 0 2))
                     (cond
                       (#{\1 \2 \5 \6} (.charAt n 6)) 1900
                       (#{\3 \4 \7 \8} (.charAt n 6)) 2000
                       :else 1800))
             month (Integer/parseInt (subs n 2 4))
             day (Integer/parseInt (subs n 4 6))
             place (Integer/parseInt (subs n 7 9))
             r (mod (long (reduce + (map * (subvec d 0 12) [2 3 4 5 6 7 8 9 2 3 4 5]))) 11)]
         (and (valid-date? year month day)
              (<= place 96)
              (= (mod (- 11 r) 10) (d 12))))))

(defn- gr-amka? [^String n]                          ; Greece AMKA: DDMMYY date and Luhn
  (and (re-matches #"\d{11}" n)
       (.isValid luhn-cd n)
       (let [day (Integer/parseInt (subs n 0 2))
             month (Integer/parseInt (subs n 2 4))
             year (+ 1900 (Integer/parseInt (subs n 4 6)))]
         (or (valid-date? year month day)
             (valid-date? (+ year 100) month day)))))

(defn- il-idnr? [^String n]                          ; Israel personal ID: 1-9 digits, left-padded Luhn
  (and (re-matches #"\d{1,9}" n)
       (pos? (Long/parseLong n))
       (.isValid luhn-cd (clojure.core/format "%09d" (Long/parseLong n)))))

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
(defn- bg-pnf? [^String n]                            ; Bulgaria PNF/LNCh: weighted mod 10
  (and (re-matches #"\d{10}" n)
       (let [d (digits-of n)]
         (= (mod (long (reduce + (map * (subvec d 0 9) [21 19 17 13 11 9 7 3 1]))) 10)
            (d 9)))))
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
(def ^:private iso6346-letter                         ; A=10..Z=38, skipping multiples of 11
  (zipmap (map char (range (int \A) (inc (int \Z))))
          (remove #(zero? (mod (long %) 11)) (range 10 39))))
(defn- iso6346? [^String n]                           ; ISO 6346 freight container (BIC) number
  (and (re-matches #"[A-Z]{3}[UJZ]\d{7}" n)
       (let [vs (concat (map iso6346-letter (subs n 0 4))
                        (map #(- (int %) 48) (subs n 4 10)))
             s (reduce + (map-indexed (fn [i v] (* (long v) (bit-shift-left 1 (int i)))) vs))]
         (= (mod (mod s 11) 10) (- (int (.charAt n 10)) 48)))))
(defn- ru-inn? [^String n]                            ; Russia INN: mod-11 mod-10 (10-digit legal, 12-digit person)
  (cond
    (re-matches #"\d{10}" n)
    (let [d (digits-of n)]
      (= (mod (mod (long (reduce + (map * (subvec d 0 9) [2 4 10 3 5 9 4 6 8]))) 11) 10) (d 9)))
    (re-matches #"\d{12}" n)
    (let [d (digits-of n)
          c11 (mod (mod (long (reduce + (map * (subvec d 0 10) [7 2 4 10 3 5 9 4 6 8]))) 11) 10)
          c12 (mod (mod (long (reduce + (map * (subvec d 0 11) [3 7 2 4 10 3 5 9 4 6 8]))) 11) 10)]
      (and (= c11 (d 10)) (= c12 (d 11))))
    :else false))
(defn- tw-digit-sum ^long [^long x] (+ (quot x 10) (mod x 10)))
(defn- tw-gui? [^String n]                            ; Taiwan Unified Business No. (統一編號): weighted, mod 5
  (and (re-matches #"\d{8}" n)
       (let [d (digits-of n)
             s (long (reduce + (map (fn [w dig] (tw-digit-sum (* (long w) (long dig)))) [1 2 1 2 1 2 4 1] d)))]
         (or (zero? (mod s 5))
             (and (= (d 6) 7) (zero? (mod (inc s) 5)))))))
(defn- ua-edrpou? [^String n]                         ; Ukraine EDRPOU (company): weighted mod 11, two weight sets
  (and (re-matches #"\d{8}" n)
       (let [d (digits-of n) v (Long/parseLong n)
             w (if (or (< v 30000000) (>= v 60000000)) [1 2 3 4 5 6 7] [7 1 2 3 4 5 6])
             c (mod (long (reduce + (map * (subvec d 0 7) w))) 11)
             c (if (= c 10)
                 (let [c2 (mod (long (reduce + (map * (subvec d 0 7) (map #(+ (long %) 2) w)))) 11)]
                   (if (= c2 10) 0 c2))
                 c)]
         (= c (d 7)))))
(def ^:private ^String usci-charset "0123456789ABCDEFGHJKLMNPQRTUWXY")  ; excludes I O S V Z
(def ^:private usci-weights [1 3 9 27 19 26 16 17 20 29 25 13 8 24 10 30 28])
(defn- cn-usci? [^String n]                           ; China Unified Social Credit Identifier (18 char, mod 31)
  (and (re-matches #"[0-9A-HJ-NP-RTUWXY]{18}" n)
       (let [vals (mapv #(.indexOf usci-charset (int %)) n)
             s (long (reduce + (map * (subvec vals 0 17) usci-weights)))]
         (= (mod (- 31 (mod s 31)) 31) (vals 17)))))
(defn- iswc? [^String n]                              ; ISWC musical-work code: T + 9 digits + mod-10 check
  (and (re-matches #"T\d{10}" n)
       (let [d (digits-of (subs n 1))
             s (long (reduce + (map-indexed (fn [i x] (* (inc (long i)) (long x))) (subvec d 0 9))))]
         (= (mod (- 10 (mod (inc s) 10)) 10) (d 9)))))
(defn- is-kennitala? [^String n]                      ; Iceland kennitala: weighted mod 11
  (and (re-matches #"\d{10}" n)
       (let [d (digits-of n)
             c (- 11 (mod (long (reduce + (map * [3 2 7 6 5 4 3 2] (subvec d 0 8)))) 11))
             c (if (= c 11) 0 c)]
         (and (not= c 10) (= c (d 8))))))
(defn- do-rnc? [^String n]                            ; Dominican Republic RNC: weighted mod 11
  (and (re-matches #"\d{9}" n)
       (let [d (digits-of n)
             s (long (reduce + (map * [7 9 8 6 5 4 3 2] (subvec d 0 8))))]
         (= (inc (mod (- 10 (mod s 11)) 9)) (d 8)))))
(defn- do-cedula? [^String n]                         ; Dominican Republic cédula: 11-digit Luhn
  (boolean (and (re-matches #"\d{11}" n) (.isValid luhn-cd n))))
(def ^:private do-ncf-types #{"01" "02" "03" "04" "11" "12" "13" "14" "15" "16" "17"})
(def ^:private do-ecf-types #{"31" "32" "33" "34" "41" "43" "44" "45" "46" "47"})
(defn- do-ncf? [^String n]                            ; Dominican Republic NCF/e-CF: structural document type
  (case (count n)
    13 (and (= \E (.charAt n 0)) (re-matches #"\d{12}" (subs n 1)) (contains? do-ecf-types (subs n 1 3)))
    11 (and (= \B (.charAt n 0)) (re-matches #"\d{10}" (subs n 1)) (contains? do-ncf-types (subs n 1 3)))
    19 (and (contains? #{\A \P} (.charAt n 0)) (re-matches #"\d{18}" (subs n 1)) (contains? do-ncf-types (subs n 9 11)))
    false))
(def ^:private ve-rif-letter {\V 1 \E 2 \J 3 \P 4 \G 5})
(defn- ve-rif? [^String n]                            ; Venezuela RIF: letter-weighted mod 11
  (and (re-matches #"[VEJPG]\d{9}" n)
       (let [d (digits-of (subs n 1))
             s (+ (* 4 (long (ve-rif-letter (.charAt n 0))))
                  (long (reduce + (map * [3 2 7 6 5 4 3 2] (subvec d 0 8)))))
             c (- 11 (mod s 11))
             c (if (>= c 10) 0 c)]
         (= c (d 8)))))
(defn- ru-ogrn? [^String n]                           ; Russia OGRN (company): check = (first12 mod 11) mod 10
  (and (re-matches #"\d{13}" n)
       (= (mod (mod (Long/parseLong (subs n 0 12)) 11) 10) (Character/digit (.charAt n 12) 10))))
(defn- vn-mst? [^String n]                            ; Vietnam tax code (MST): 10 digits (+ optional 3-digit branch), weighted mod 11
  (and (re-matches #"\d{10}(?:\d{3})?" n)
       (let [d (digits-of (subs n 0 10))
             s (long (reduce + (map * [31 29 23 19 17 13 7 5 3] (subvec d 0 9))))]
         (= (mod (- 10 (mod s 11)) 10) (d 9)))))
(defn- rs-pib? [^String n]                            ; Serbia PIB (tax): ISO 7064 MOD 11,10
  (and (re-matches #"\d{9}" n)
       (let [d (digits-of n)
             p (long (reduce (fn [p i]
                               (let [s (mod (+ (d i) (long p)) 10)
                                     s (if (zero? s) 10 s)]
                                 (mod (* s 2) 11)))
                             10 (range 8)))]
         (= (mod (- 11 p) 10) (d 8)))))
(defn- me-pib? [^String n]                            ; Montenegro PIB: weighted mod 11 then mod 10
  (and (re-matches #"\d{8}" n)
       (let [d (digits-of n)
             s (long (reduce + (map * (subvec d 0 7) [8 7 6 5 4 3 2])))]
         (= (mod (mod (- s) 11) 10) (d 7)))))
(defn- mk-edb? [^String n]                            ; North Macedonia EDB: optional MK + weighted mod 11 then mod 10
  (let [n (strip-cc n "MK")]
    (and (re-matches #"\d{13}" n)
         (let [d (digits-of n)
               s (long (reduce + (map * (subvec d 0 12) [7 6 5 4 3 2 7 6 5 4 3 2])))]
           (= (mod (mod (- s) 11) 10) (d 12))))))
(defn- pl-regon? [^String n]                          ; Poland REGON (9-digit): weighted mod 11
  (and (re-matches #"\d{9}" n)
       (let [d (digits-of n)
             c (mod (mod (long (reduce + (map * [8 9 2 3 4 5 6 7] (subvec d 0 8)))) 11) 10)]
         (= c (d 8)))))
(defn- il-company? [^String n]                        ; Israel company/ID number: 9-digit Israeli Luhn (weights 1,2,1,2,…)
  (and (re-matches #"\d{9}" n)
       (zero? (mod (long (reduce + (map-indexed
                                    (fn [i d] (let [x (* (long d) (if (even? (long i)) 1 2))]
                                                (if (> x 9) (- x 9) x)))
                                    (digits-of n))))
                   10))))
(defn- au-acn? [^String n]                            ; Australia Company Number (ACN): weights 8..1, complement mod 10
  (and (re-matches #"\d{9}" n)
       (let [d (digits-of n)]
         (= (mod (- 10 (mod (long (reduce + (map * (subvec d 0 8) [8 7 6 5 4 3 2 1]))) 10)) 10) (d 8)))))
(defn- sk-ico? [^String n]                            ; Slovakia IČO: weighted mod 11 (same Czechoslovak algorithm as Czech IČO)
  (and (re-matches #"\d{8}" n)
       (let [d (digits-of n)
             r (mod (long (reduce + (map * (subvec d 0 7) [8 7 6 5 4 3 2]))) 11)]
         (= (mod (- 11 r) 10) (d 7)))))
(defn- ee-rk? [^String n]                             ; Estonia registry code (registrikood): mod-11 with weight-set fallback
  (and (re-matches #"\d{8}" n)
       (let [d (digits-of n)
             c (mod (long (reduce + (map * (subvec d 0 7) [1 2 3 4 5 6 7]))) 11)
             c (if (= c 10)
                 (let [c2 (mod (long (reduce + (map * (subvec d 0 7) [3 4 5 6 7 8 9]))) 11)]
                   (if (= c2 10) 0 c2))
                 c)]
         (= c (d 7)))))
(defn- uy-rut? [^String n]                            ; Uruguay RUT (tax): 12-digit weighted mod 11
  (and (re-matches #"\d{12}" n)
       (let [d (digits-of n)
             s (long (reduce + (map * [4 3 2 9 8 7 6 5 4 3 2] (subvec d 0 11))))]
         (= (mod (- 11 (mod s 11)) 11) (d 11)))))
(defn- ec-ruc? [^String n]                            ; Ecuador RUC: 13-digit; 3rd digit selects class (0-5 natural, 6 public, 9 juridical)
  (and (re-matches #"\d{13}" n)
       (<= 1 (Integer/parseInt (subs n 0 2)) 24)
       (let [d (digits-of n) t (d 2)]
         (cond
           (<= t 5) (and (ec-ced? (subs n 0 10)) (not= "000" (subs n 10)))
           (= t 6)  (let [r (mod (long (reduce + (map * (subvec d 0 8) [3 2 7 6 5 4 3 2]))) 11)
                          c (if (zero? r) 0 (- 11 r))]
                      (and (< c 10) (= c (d 8)) (not= "0000" (subs n 9))))
           (= t 9)  (let [r (mod (long (reduce + (map * (subvec d 0 9) [4 3 2 7 6 5 4 3 2]))) 11)
                          c (if (zero? r) 0 (- 11 r))]
                      (and (< c 10) (= c (d 9)) (not= "000" (subs n 10))))
           :else false))))
(defn- py-ruc? [^String n]                            ; Paraguay RUC: base + check digit, weighted mod 11 (>=10 -> 0)
  (and (re-matches #"\d{6,9}" n)
       (let [d (digits-of n) k (dec (count d))
             s (long (reduce + (map-indexed (fn [i x] (* (+ (long i) 2) (long x))) (reverse (subvec d 0 k)))))
             c (- 11 (mod s 11))
             c (if (>= c 10) 0 c)]
         (= c (d k)))))
(defn- gt-nit? [^String n]                            ; Guatemala NIT: base + check (digit or K), weighted mod 11
  (and (re-matches #"\d{4,12}[0-9K]" n)
       (let [k (dec (count n))
             s (long (reduce + (map-indexed (fn [i c] (* (+ (long i) 2) (long (- (int c) 48))))
                                            (reverse (subs n 0 k)))))
             r (mod (- 11 (mod s 11)) 11)
             exp (if (= r 10) (int \K) (+ 48 r))]
         (= (int (.charAt n k)) exp))))
(defn- fr-siren? [^String n]                          ; France SIREN: 9-digit Luhn
  (boolean (and (re-matches #"\d{9}" n) (.isValid luhn-cd n))))
(defn- fr-siret? [^String n]                          ; France SIRET: SIREN + 5-digit NIC, 14-digit Luhn
  (boolean (and (re-matches #"\d{14}" n) (.isValid luhn-cd n))))
(defn- fr-nif? [^String n]                            ; France NIF: first 10 digits mod 511 == last 3
  (and (re-matches #"[0-3]\d{12}" n)
       (= (mod (Long/parseLong (subs n 0 10)) 511)
          (Integer/parseInt (subs n 10)))))
(defn- al-nipt? [^String n]                           ; Albania NIPT/NUIS: structural, optional AL country marker
  (let [n (strip-cc n "AL")]
    (boolean (re-matches #"[A-M][0-9]{8}[A-Z]" n))))
(defn- ar-dni? [^String n]                            ; Argentina DNI: 7 or 8 digits
  (boolean (re-matches #"\d{7,8}" n)))
(def ^:private ca-bcphn-weights [2 4 8 5 10 9 7 3])
(defn- ca-bcphn-check-digit ^long [^String n]
  (mod (- 11 (mod (long (reduce + (map (fn [w d] (mod (* (long w) (long d)) 11))
                                        ca-bcphn-weights
                                        (digits-of n))))
                  11))
       11))
(defn- ca-bcphn? [^String n]                          ; British Columbia PHN: 9 + MOD-11 check
  (and (re-matches #"9\d{9}" n)
       (= (ca-bcphn-check-digit (subs n 1 9)) (- (int (.charAt n 9)) 48))))
(defn- dz-nif? [^String n]                            ; Algeria NIF: 15 or 20 digits
  (boolean (re-matches #"\d{15}(\d{5})?" n)))
(def ^:private arabic-digits
  {\٠ \0 \١ \1 \٢ \2 \٣ \3 \٤ \4 \٥ \5 \٦ \6 \٧ \7 \٨ \8 \٩ \9
   \۰ \0 \۱ \1 \۲ \2 \۳ \3 \۴ \4 \۵ \5 \۶ \6 \۷ \7 \۸ \8 \۹ \9})
(defn- ascii-digits ^String [^String n]
  (apply str (map #(get arabic-digits % %) n)))
(defn- eg-tn? [^String n]                             ; Egypt tax number: 9 digits, Arabic digits accepted
  (boolean (re-matches #"\d{9}" (ascii-digits n))))
(defn- gh-tin-check-digit [^String n]
  (let [check (mod (long (reduce + (map-indexed (fn [i c] (* (inc (long i)) (- (int c) 48)))
                                                (subs n 1 10))))
                   11)]
    (if (= 10 check) \X (char (+ 48 check)))))
(defn- gh-tin? [^String n]                            ; Ghana TIN: prefix + mod-11 check
  (and (re-matches #"[PCGQV]00[A-Z0-9]{8}" n)
       (= (int (gh-tin-check-digit n)) (int (.charAt n 10)))))
(defn- gn-nifp? [^String n]                           ; Guinea NIFp: 9-digit Luhn
  (boolean (and (re-matches #"\d{9}" n) (.isValid luhn-cd n))))
(defn- ma-ice? [^String n]                            ; Morocco ICE: 15 digits, ISO 7064 MOD 97-10
  (and (re-matches #"\d{15}" n)
       (zero? (reduce (fn [^long r c] (mod (+ (* r 10) (- (int c) 48)) 97)) 0 n))))
(def ^:private tn-mf-control-keys (set "ABCDEFGHJKLMNPQRSTVWXYZ"))
(def ^:private tn-mf-tva-codes #{\A \P \B \D \N})
(def ^:private tn-mf-category-codes #{\M \P \C \N \E})
(defn- tn-mf-compact ^String [^String n]
  (if-let [[_ serial rest] (re-matches #"([0-9]+)(.*)" n)]
    (str (clojure.core/format "%07d" (Long/parseLong serial)) rest)
    n))
(defn- tn-mf? [^String n]                             ; Tunisia MF: serial/control and optional TVA/category/branch
  (let [n (tn-mf-compact n)]
    (and (or (= 8 (count n)) (= 13 (count n)))
         (re-matches #"\d{7}.*" n)
         (contains? tn-mf-control-keys (.charAt n 7))
         (or (= 8 (count n))
             (and (contains? tn-mf-tva-codes (.charAt n 8))
                  (contains? tn-mf-category-codes (.charAt n 9))
                  (re-matches #"\d{3}" (subs n 10))
                  (or (= "000" (subs n 10)) (= \E (.charAt n 9))))))))
(defn- sv-nit-check-digit ^long [^String n]
  (let [weights (if (not (pos? (compare (subs n 10 13) "100")))
                  [14 13 12 11 10 9 8 7 6 5 4 3 2]
                  [2 7 6 5 4 3 2 7 6 5 4 3 2])
        total (long (reduce + (map * (digits-of (subs n 0 13)) weights)))]
    (if (not (pos? (compare (subs n 10 13) "100")))
      (mod (mod total 11) 10)
      (mod (mod (- total) 11) 10))))
(defn- sv-nit? [^String n]                            ; El Salvador NIT: component + old/new weighted check
  (let [n (strip-cc n "SV")]
    (and (re-matches #"[019]\d{13}" n)
         (= (sv-nit-check-digit n) (- (int (.charAt n 13)) 48)))))
(defn- li-peid? [^String n]                           ; Liechtenstein PEID: 4-12 digits after leading zero trim
  (let [n (str/replace n #"^0+" "")]
    (boolean (and (<= 4 (count n) 12) (re-matches #"\d+" n)))))
(def ^:private sm-coe-low-numbers
  #{2 4 6 7 8 9 10 11 13 16 18 19 20 21 25 26 30 32 33 35 36 37 38 39 40
    42 45 47 49 51 52 55 56 57 58 59 61 62 64 65 66 67 68 69 70 71 72 73
    74 75 76 79 80 81 84 85 87 88 91 92 94 95 96 97 99})
(defn- sm-coe? [^String n]                            ; San Marino COE: 1-5 digits, low-number registry check
  (let [n (str/replace n #"^0+" "")]
    (and (re-matches #"\d{1,5}" n)
         (or (>= (count n) 3) (contains? sm-coe-low-numbers (Integer/parseInt n))))))
(defn- se-orgnr? [^String n]                          ; Sweden organisationsnummer: 10-digit Luhn, 3rd digit >= 2
  (boolean (and (re-matches #"\d{10}" n) (>= (- (int (.charAt n 2)) 48) 2) (.isValid luhn-cd n))))
(defn- es-cif? [^String n]                            ; Spain CIF: org-letter + 7 digits + control (digit or letter)
  (and (re-matches #"[ABCDEFGHJNPQRSUVW]\d{7}[0-9A-J]" n)
       (let [d (digits-of (subs n 1 8))
             odd (long (reduce + (map (fn [x] (let [y (* 2 (long x))] (if (> y 9) (- y 9) y)))
                                      [(d 0) (d 2) (d 4) (d 6)])))
             even (long (+ (d 1) (d 3) (d 5)))
             c (mod (+ odd even) 10)
             c (if (zero? c) 0 (- 10 c))
             ctrl (.charAt n 8)]
         (or (= (int ctrl) (+ 48 c))
             (= (int ctrl) (int (.charAt "JABCDEFGHI" c)))))))
(defn- es-nif? [^String n]                            ; Spain NIF: DNI, NIE, CIF, or K/L/M DNI-check form
  (let [n (strip-cc n "ES")]
    (boolean
     (or (es-dni? n)
         (es-nie? n)
         (es-cif? n)
         (and (re-matches #"[KLM]\d{7}[A-Z]" n)
              (= (.charAt dni-letters (int (mod (Long/parseLong (subs n 1 8)) 23)))
                 (.charAt n 8)))))))

(def ^:private es-cae-offices
  #{"01" "02" "03" "04" "05" "06" "07" "08" "09" "10" "11" "12" "13" "14" "15" "16"
    "17" "18" "19" "20" "21" "22" "23" "24" "25" "26" "27" "28" "29" "30" "31" "32"
    "33" "34" "35" "36" "37" "38" "39" "40" "41" "42" "43" "44" "45" "46" "47" "48"
    "49" "50" "51" "52" "53" "54" "55" "56"})

(def ^:private es-cae-activity-keys
  #{"A1" "B1" "B9" "B0" "BA" "C1" "DA" "EC" "F1" "V1" "A7" "AT" "B7" "BT" "C7" "DB"
    "E7" "M7" "OA" "OB" "OE" "OV" "V7" "B6" "A2" "A6" "A9" "A0" "AC" "AV" "AW" "AX"
    "H1" "H2" "H4" "H6" "H9" "H0" "HD" "HH" "H7" "H8" "HB" "HF" "HI" "HJ" "HK" "HL"
    "HM" "HN" "HT" "HU" "HV" "HX" "HZ" "OH" "HA" "HC" "HE" "HP" "HQ" "HR" "HS" "HW"
    "T1" "OT" "T7" "TT" "L1" "L2" "L0" "L3" "L7" "AF" "DF" "DM" "DP" "OR" "PF" "RF"
    "VD" "F7" "GP"})

(defn- es-cae? [^String n]
  (and (re-matches #"ES000[A-Z0-9]{8}" n)
       (contains? es-cae-offices (subs n 5 7))
       (contains? es-cae-activity-keys (subs n 7 9))
       (re-matches #"\d{3}" (subs n 9 12))
       (Character/isLetter (.charAt n 12))))

(def ^:private ^String es-cups-check-letters "TRWAGMYFPDXBNJZSQVHLCKE")
(defn- es-cups-check-digits ^String [^String n]
  (let [r (mod (Long/parseLong (subs n 2 18)) 529)
        check0 (quot r 23)
        check1 (mod r 23)]
    (str (.charAt es-cups-check-letters check0)
         (.charAt es-cups-check-letters check1))))
(defn- es-cups? [^String n]
  (and (re-matches #"ES\d{16}[A-Z]{2}(\d[FPRCXYZ])?" n)
       (= (es-cups-check-digits n) (subs n 18 20))))

(defn- es-postalcode? [^String n]
  (and (re-matches #"\d{5}" n)
       (<= 1 (Integer/parseInt (subs n 0 2)) 52)))

(def ^:private ^String es-cadastral-alphabet "ABCDEFGHIJKLMNÑOPQRSTUVWXYZ0123456789")
(def ^:private ^String es-cadastral-check-letters "MQWERTYUIOPASDFGHJKLBZX")
(defn- es-cadastral-char-value ^long [^Character c]
  (if (Character/isDigit (char c))
    (- (int c) 48)
    (inc (.indexOf es-cadastral-alphabet (int c)))))
(defn- es-cadastral-check-digit [^String part]
  (let [s (long (reduce + (map (fn [w c] (* (long w) (es-cadastral-char-value c)))
                               [13 15 12 5 4 17 9 21 3 7 1] part)))]
    (.charAt es-cadastral-check-letters (int (mod s 23)))))
(defn- es-referenciacatastral? [^String n]
  (and (= 20 (count n))
       (every? #(not (neg? (.indexOf es-cadastral-alphabet (int %)))) n)
       (= (str (es-cadastral-check-digit (str (subs n 0 7) (subs n 14 18)))
               (es-cadastral-check-digit (str (subs n 7 14) (subs n 14 18))))
          (subs n 18))))

(defn- at-businessid? [^String n]
  (let [n (if (str/starts-with? n "FN") (subs n 2) n)]
    (boolean (re-matches #"\d+[A-Z]" n))))

(defn- at-postleitzahl? [^String n]                   ; Austria PLZ: 4-digit structural (1000-9999)
  (boolean (re-matches #"[1-9]\d{3}" n)))

(def ^:private at-tin-offices
  #{"03" "04" "06" "07" "08" "09" "10" "12" "15" "16" "18" "22" "23" "29" "33" "38"
    "41" "46" "51" "52" "53" "54" "57" "59" "61" "65" "67" "68" "69" "71" "72" "81"
    "82" "83" "84" "90" "91" "93" "97" "98"})
(defn- at-tin-check-digit ^long [^String n]
  (mod (- 10 (long (reduce + (map-indexed
                              (fn [i c]
                                (let [d (- (int c) 48)]
                                  (if (odd? i) (- (int (.charAt "0246813579" d)) 48) d)))
                              (subs n 0 8)))))
       10))
(defn- at-tin? [^String n]
  (and (re-matches #"\d{9}" n)
       (= (at-tin-check-digit n) (- (int (.charAt n 8)) 48))
       (contains? at-tin-offices (subs n 0 2))))

(defn- at-vnr-check-digit ^long [^String n]
  (mod (long (reduce + (map * [3 7 9 0 5 8 4 2 1 6] (digits-of n)))) 11))
(defn- at-vnr? [^String n]
  (and (re-matches #"[1-9]\d{9}" n)
       (= (at-vnr-check-digit n) (- (int (.charAt n 3)) 48))))

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
(defn- nz-nzbn? [^String n] (and (re-matches #"9429\d{9}" n) (.isValid ean13-cd n)))  ; NZ Business Number: GS1 GLN, 9429 prefix
(defn- id-npwp? [^String n]                           ; Indonesia NPWP: classic 15-digit, Luhn over the first 9
  (and (re-matches #"\d{15}" n) (.isValid luhn-cd (subs n 0 9))))

(defn- dk-cpr-year [^String n]
  (let [year (Integer/parseInt (subs n 4 6))
        century (.charAt n 6)]
    (cond
      (and (#{\5 \6 \7 \8} century) (>= year 58)) (+ 1800 year)
      (or (#{\0 \1 \2 \3} century) (and (#{\4 \9} century) (>= year 37))) (+ 1900 year)
      :else (+ 2000 year))))

(defn- dk-cpr? [^String n]                            ; Denmark CPR: date + century from serial, no checksum
  (and (re-matches #"\d{10}" n)
       (let [day (Integer/parseInt (subs n 0 2))
             month (Integer/parseInt (subs n 2 4))
             year (dk-cpr-year n)]
         (and (valid-date? year month day)
              (not (.isAfter (java.time.LocalDate/of (long year) (long month) (long day))
                             (java.time.LocalDate/now)))))))

(def ^:private pk-cnic-provinces #{\1 \2 \3 \4 \5 \6 \7})
(defn- pk-cnic? [^String n]                           ; Pakistan CNIC: province + serial + gender digit
  (and (re-matches #"\d{13}" n)
       (contains? pk-cnic-provinces (.charAt n 0))
       (contains? #{\1 \2 \3 \4 \5 \6 \7 \8 \9} (.charAt n 12))))

(def ^:private my-nric-birthplaces
  #{"01" "02" "03" "04" "05" "06" "07" "08" "09" "10" "11" "12" "13" "14" "15" "16"
    "21" "22" "23" "24" "25" "26" "27" "28" "29" "30" "31" "32" "33" "34" "35" "36"
    "37" "38" "39" "40" "41" "42" "43" "44" "45" "46" "47" "48" "49" "50" "51" "52"
    "53" "54" "55" "56" "57" "58" "59" "60" "61" "62" "63" "64" "65" "66" "67" "68"
    "71" "72" "74" "75" "76" "77" "78" "79" "82" "83" "84" "85" "86" "87" "88" "89"
    "90" "91" "92" "93" "98" "99"})

(defn- my-nric-date? [^String n]
  (let [year (Integer/parseInt (subs n 0 2))
        month (Integer/parseInt (subs n 2 4))
        day (Integer/parseInt (subs n 4 6))]
    (or (valid-date? (+ 1900 year) month day)
        (valid-date? (+ 2000 year) month day))))

(defn- my-nric? [^String n]                           ; Malaysia NRIC: date + birthplace code
  (and (re-matches #"\d{12}" n)
       (my-nric-date? n)
       (contains? my-nric-birthplaces (subs n 6 8))))

(def ^:private id-nik-locations
  #{"1101" "1102" "1103" "1104" "1105" "1106" "1107" "1108" "1109" "1110" "1111" "1112"
    "1113" "1114" "1115" "1116" "1117" "1118" "1171" "1172" "1173" "1174" "1175" "1201"
    "1202" "1203" "1204" "1205" "1206" "1207" "1208" "1209" "1210" "1211" "1212" "1213"
    "1214" "1215" "1216" "1217" "1218" "1219" "1220" "1221" "1222" "1223" "1224" "1225"
    "1271" "1272" "1273" "1274" "1275" "1276" "1277" "1278" "1301" "1302" "1303" "1304"
    "1305" "1306" "1307" "1308" "1309" "1310" "1311" "1312" "1371" "1372" "1373" "1374"
    "1375" "1376" "1377" "1401" "1402" "1403" "1404" "1405" "1406" "1407" "1408" "1409"
    "1410" "1471" "1473" "1501" "1502" "1503" "1504" "1505" "1506" "1507" "1508" "1509"
    "1571" "1572" "1601" "1602" "1603" "1604" "1605" "1606" "1607" "1608" "1609" "1610"
    "1611" "1671" "1672" "1673" "1674" "1701" "1702" "1703" "1704" "1705" "1706" "1707"
    "1708" "1709" "1771" "1801" "1802" "1803" "1804" "1805" "1806" "1807" "1808" "1809"
    "1810" "1811" "1812" "1871" "1872" "1901" "1902" "1903" "1904" "1905" "1906" "1971"
    "2101" "2102" "2103" "2104" "2105" "2171" "2172" "3101" "3171" "3172" "3173" "3174"
    "3175" "3201" "3202" "3203" "3204" "3205" "3206" "3207" "3208" "3209" "3210" "3211"
    "3212" "3213" "3214" "3215" "3216" "3217" "3271" "3272" "3273" "3274" "3275" "3276"
    "3277" "3278" "3279" "3301" "3302" "3303" "3304" "3305" "3306" "3307" "3308" "3309"
    "3310" "3311" "3312" "3313" "3314" "3315" "3316" "3317" "3318" "3319" "3320" "3321"
    "3322" "3323" "3324" "3325" "3326" "3327" "3328" "3329" "3371" "3372" "3373" "3374"
    "3375" "3376" "3401" "3402" "3403" "3404" "3471" "3501" "3502" "3503" "3504" "3505"
    "3506" "3507" "3508" "3509" "3510" "3511" "3512" "3513" "3514" "3515" "3516" "3517"
    "3518" "3519" "3520" "3521" "3522" "3523" "3524" "3525" "3526" "3527" "3528" "3529"
    "3571" "3572" "3573" "3574" "3575" "3576" "3577" "3578" "3579" "3601" "3602" "3603"
    "3604" "3671" "3672" "3673" "3674" "5101" "5102" "5103" "5104" "5105" "5106" "5107"
    "5108" "5171" "5201" "5202" "5203" "5204" "5205" "5206" "5207" "5208" "5271" "5272"
    "5301" "5302" "5303" "5304" "5305" "5306" "5307" "5308" "5309" "5310" "5311" "5312"
    "5313" "5314" "5315" "5316" "5317" "5318" "5319" "5320" "5371" "6101" "6102" "6103"
    "6104" "6105" "6106" "6107" "6108" "6109" "6110" "6111" "6112" "6171" "6172" "6201"
    "6202" "6203" "6204" "6205" "6206" "6207" "6208" "6209" "6210" "6211" "6212" "6213"
    "6271" "6301" "6302" "6303" "6304" "6305" "6306" "6307" "6308" "6309" "6310" "6311"
    "6371" "6372" "6401" "6402" "6403" "6404" "6405" "6406" "6407" "6408" "6409" "6410"
    "6471" "6472" "6473" "6474" "7101" "7102" "7103" "7104" "7105" "7106" "7107" "7108"
    "7109" "7110" "7111" "7171" "7172" "7173" "7174" "7201" "7202" "7203" "7204" "7205"
    "7206" "7207" "7208" "7209" "7210" "7271" "7301" "7302" "7303" "7304" "7305" "7306"
    "7307" "7308" "7309" "7310" "7311" "7312" "7313" "7314" "7315" "7316" "7317" "7318"
    "7322" "7325" "7326" "7371" "7372" "7373" "7401" "7402" "7403" "7404" "7405" "7406"
    "7407" "7408" "7409" "7410" "7471" "7472" "7501" "7502" "7503" "7504" "7505" "7571"
    "7601" "7602" "7603" "7604" "7605" "8101" "8102" "8103" "8104" "8105" "8106" "8107"
    "8108" "8109" "8171" "8172" "8201" "8202" "8203" "8204" "8205" "8206" "8207" "8271"
    "8272" "9101" "9102" "9103" "9104" "9105" "9106" "9107" "9108" "9109" "9110" "9171"
    "9401" "9402" "9403" "9404" "9408" "9409" "9410" "9411" "9412" "9413" "9414" "9415"
    "9416" "9417" "9418" "9419" "9420" "9426" "9427" "9428" "9429" "9430" "9431" "9432"
    "9433" "9434" "9435" "9436" "9471"})

(defn- id-nik-date? [^String n]
  (let [day (mod (Integer/parseInt (subs n 6 8)) 40)
        month (Integer/parseInt (subs n 8 10))
        year (Integer/parseInt (subs n 10 12))]
    (or (valid-date? (+ 1900 year) month day)
        (valid-date? (+ 2000 year) month day))))

(defn- id-nik? [^String n]                            ; Indonesia NIK: location + date, no checksum
  (and (re-matches #"\d{16}" n)
       (id-nik-date? n)
       (contains? id-nik-locations (subs n 0 4))))

(defn- ke-pin? [^String n]                            ; Kenya PIN: A/P + 9 digits + letter
  (boolean (and (= 11 (count n)) (re-matches #"[AP]\d{9}[A-Z]" n))))

(defn- za-tin? [^String n]                            ; South Africa TIN: leading digit + Luhn
  (and (re-matches #"\d{10}" n)
       (contains? #{\0 \1 \2 \3 \9} (.charAt n 0))
       (.isValid luhn-cd n)))

(defn- tr-vkn? [^String n]                            ; Turkey VKN (tax/entity no.): 10-digit, weighted mod-9 + mod-10 check
  (and (re-matches #"\d{10}" n)
       (let [d (digits-of n)
             s (long (reduce + (for [i (range 9)]
                                 (let [c1 (mod (+ (long (d i)) (- 9 (long i))) 10)
                                       c2 (mod (* c1 (bit-shift-left 1 (- 9 (long i)))) 9)]
                                   (if (and (not (zero? c1)) (zero? c2)) 9 c2)))))]
         (= (mod (- 10 (mod s 10)) 10) (d 9)))))
(def ^:private ^String mx-rfc-alpha "0123456789ABCDEFGHIJKLMN&OPQRSTUVWXYZ Ñ")
(defn- mx-rfc? [^String n]                            ; Mexico RFC: SAT mod-11 over a value table (final char is the check)
  (and (re-matches #"[A-ZÑ&]{3,4}\d{6}[A-Z0-9]{3}" n)
       (let [k (dec (count n))
             s (str "   " (subs n 0 k))
             padded (subs s (- (count s) 12))
             check (long (reduce + (map-indexed (fn [i c] (* (.indexOf mx-rfc-alpha (int c)) (- 13 (long i)))) padded)))]
         (= (.charAt mx-rfc-alpha (int (mod (- 11 check) 11))) (.charAt n (int k))))))
(def ^:private ^String m3736-alpha "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")  ; ISO 7064 Mod 37,36 (radix 36)
(defn- eu-ecnumber? [^String n]                      ; EC number: 7 digits, weighted mod 11
  (and (re-matches #"\d{7}" n)
       (let [d (digits-of n)]
         (= (str (mod (long (reduce + (map * (subvec d 0 6) (range 1 7)))) 11))
            (str (d 6))))))
(def ^:private ^String eu-banknote-letters "BCDEFGHJLMNPRSTUVWXYZ")
(defn- eu-banknote-checksum ^long [^String n]
  (mod (long (reduce + (map (fn [c] (if (Character/isDigit ^char c) (- (int c) 48) (int c))) n))) 9))
(defn- eu-banknote? [^String n]                      ; Euro banknote serial: one/two letters, checksum mod 9
  (and (re-matches #"[A-Z0-9]{2}\d{10}" n)
       (not= -1 (.indexOf eu-banknote-letters (int (.charAt n 0))))
       (zero? (eu-banknote-checksum n))))
(defn- pt-cc-check-digit [^String n]                 ; Portugal CC: python-stdnum radix-36 Luhn variant
  (let [s (long (reduce + (map-indexed
                           (fn [i c]
                             (let [v (.indexOf m3736-alpha (int c))
                                   x (* 2 v)]
                               (if (even? i) (if (> x 9) (- x 9) x) v)))
                           (reverse n))))]
    (str (mod (- 10 s) 10))))
(defn- pt-cc? [^String n]
  (if (re-matches #"\d*[A-Z0-9]{2}\d" n)
    (let [k (dec (count n))
          base (subs n 0 k)]
      (= (pt-cc-check-digit base) (subs n k)))
    false))
(defn- iso7064-mod37-36-valid? [^String s]            ; whole string incl. check char; valid when checksum == 1
  (= 1 (reduce (fn [^long c ch]
                 (let [idx (.indexOf m3736-alpha (int ch))]
                   (if (neg? idx) (reduced -1)
                     (mod (+ (mod (* (if (zero? c) 36 c) 2) 37) idx) 36))))
               18 s)))
(defn- grid? [^String n]                              ; GRid (Global Release Identifier): 18 alnum, ISO 7064 Mod 37,36
  (and (re-matches #"[0-9A-Z]{18}" n) (iso7064-mod37-36-valid? n)))
(defn- isan? [^String n]                              ; ISAN (ISO 15706): root12+episode4 +check1 +version8 +check2, two Mod 37,36 checks
  (and (re-matches #"[0-9A-F]{16}[0-9A-Z][0-9A-F]{8}[0-9A-Z]" n)
       (iso7064-mod37-36-valid? (subs n 0 17))                    ; check1 over root+episode
       (iso7064-mod37-36-valid? (str (subs n 0 16) (subs n 17))))) ; check2 over root+episode+version
(defn- th-moa? [^String n]                            ; Thailand company tax ID (MOA): 13-digit, leading 0, weighted mod 11
  (and (re-matches #"0\d{12}" n)
       (let [d (digits-of n)
             s (long (reduce + (map * (subvec d 0 12) [13 12 11 10 9 8 7 6 5 4 3 2])))]
         (= (mod (- 11 (mod s 11)) 10) (d 12)))))
(defn- th-pin? [^String n]                            ; Thailand personal ID: 13-digit, weighted mod 11
  (and (re-matches #"[1-8]\d{12}" n)
       (let [d (digits-of n)
             s (long (reduce + (map * (subvec d 0 12) [13 12 11 10 9 8 7 6 5 4 3 2])))]
         (= (mod (- 11 (mod s 11)) 10) (d 12)))))
(defn- th-tin? [^String n] (or (th-moa? n) (th-pin? n))) ; Thailand TIN dispatches to MOA or PIN

(defn- in-epic? [^String n]                           ; India EPIC: 3 letters + 7 Luhn digits
  (and (re-matches #"[A-Z]{3}\d{7}" n)
       (.isValid luhn-cd (subs n 3))))
(defn- in-vid? [^String n]                            ; India VID: 16 digits, leading 2-9, Verhoeff, non-palindrome
  (and (re-matches #"[2-9]\d{15}" n)
       (not= n (str/reverse n))
       (.isValid verhoeff-cd n)))
(defn- jp-in? [^String n]                             ; Japan Individual Number: 12 digits, weighted check
  (and (re-matches #"\d{12}" n)
       (let [d (digits-of n)
             s (long (reduce + (map * (subvec d 0 11) [6 5 4 3 2 7 6 5 4 3 2])))]
         (= (mod (mod (- s) 11) 10) (d 11)))))
(defn- pe-cui-check-digits ^String [^String n]
  (let [c (mod (long (reduce + (map * (digits-of (subs n 0 8)) [3 2 7 6 5 4 3 2]))) 11)]
    (str (.charAt "65432110987" c) (.charAt "KJIHGFEDCBA" c))))
(defn- pe-cui? [^String n]                            ; Peru CUI: 8 digits plus optional digit check
  (and (re-matches #"\d{8,9}" n)
       (or (= 8 (count n))
           (not= -1 (.indexOf (pe-cui-check-digits n) (int (.charAt n 8)))))))

(def ^:private stnr-format-strings
  ["FFBBBUUUUP" "28FF0BBBUUUUP" "FFFBBBUUUUP" "9FFF0BBBUUUUP"
   "11FF0BBBUUUUP" "0FFBBBUUUUP" "30FF0BBBUUUUP" "24FF0BBBUUUUP"
   "22FF0BBBUUUUP" "26FF0BBBUUUUP" "40FF0BBBUUUUP" "23FF0BBBUUUUP"
   "FFFBBBBUUUP" "5FFF0BBBBUUUP" "27FF0BBBUUUUP" "10FF0BBBUUUUP"
   "2FFBBBUUUUP" "32FF0BBBUUUUP" "1FFBBBUUUUP" "31FF0BBBUUUUP"
   "21FF0BBBUUUUP" "41FF0BBBUUUUP"])
(defn- stnr-format-regex [fmt]
  (re-pattern
   (str "^" (apply str (map #(if (#{\F \B \U \P} %) "\\d" (str %)) fmt)) "$")))
(def ^:private stnr-format-res (mapv stnr-format-regex stnr-format-strings))
(defn- de-stnr? [^String n]                           ; German tax number: regional/country structural formats
  (and (re-matches #"\d{10}|\d{11}|\d{13}" n)
       (some #(re-matches % n) stnr-format-res)))

(defn- de-handelsregisternummer? [^String n]          ; German register no.; court table intentionally structural
  (let [n (str/trim n)
        registry "(HRA|HRB|PR|GNR|VR)"
        number "([1-9][0-9]{0,5})(\\s+[A-ZÖ]{1,3})?"
        court ".+?"]
    (boolean
     (or (re-matches (re-pattern (str "(?iu)" registry "\\s+" number ",?\\s+" court)) n)
         (re-matches (re-pattern (str "(?iu)" court ",?\\s+" registry "\\s+" number)) n)))))

(defn- cz-bankaccount-checksum ^long [^String n]
  (mod (long (reduce + (map * [6 3 7 9 10 5 8 4 2 1] (digits-of (clojure.core/format "%010d" (Long/parseLong n)))))) 11))
(defn- cz-bankaccount? [^String n]                    ; Czech bank account: prefix/root mod-11 + present bank code
  (let [n (str/replace (str n) #"\s" "")]
    (when-let [[_ prefix root bank] (re-matches #"(?:(\d{0,6})-)?(\d{2,10})/(\d{4})" n)]
      (and (not= "0000" bank)
           (zero? (cz-bankaccount-checksum (or prefix "")))
           (zero? (cz-bankaccount-checksum root))))))

(defn- eu-at02-base10 ^String [^String n]
  (apply str (map #(.indexOf m3736-alpha (int %)) (str (subs n 7) (subs n 0 4)))))
(defn- eu-at02? [^String n]                           ; SEPA AT-02: ISO 7064 Mod 97-10, business code skipped
  (and (re-matches #"[A-Z]{2}\d{2}[0-9A-Z]{3}[0-9A-Z]+" n)
       (= 1 (lei-mod97 (eu-at02-base10 n)))))
(defn- eu-nace? [^String n]                           ; NACE structural only; python checks bundled code tables
  (and (<= 1 (count n) 4)
       (if (= 1 (count n))
         (boolean (re-matches #"[A-Z]" n))
         (boolean (re-matches #"\d+" n)))))
(def ^:private eu-oss-member-states
  #{"040" "056" "100" "191" "196" "203" "208" "233" "246" "250" "276" "300" "348" "372"
    "380" "428" "440" "442" "470" "528" "616" "620" "642" "703" "705" "724" "752" "900"})
(defn- eu-oss? [^String n]                            ; EU OSS/IOSS structural; no public checksum
  (and (or (and (str/starts-with? n "EU") (= 11 (count n)))
           (and (str/starts-with? n "IM") (= 12 (count n))))
       (re-matches #"\d+" (subs n 2))
       (contains? eu-oss-member-states (subs n 2 5))))

(def ^:private nz-bankaccount-algorithms
  {"01" "A", "02" "A", "03" "A", "04" "A", "06" "A", "08" "D", "09" "E", "10" "A", "11" "A"
   "12" "A", "13" "A", "14" "A", "15" "A", "16" "A", "17" "A", "18" "A", "19" "A", "20" "A"
   "21" "A", "22" "A", "23" "A", "24" "A", "25" "F", "26" "G", "27" "A", "28" "G", "29" "G"
   "30" "A", "31" "X", "33" "F", "35" "A", "38" "A"})
(def ^:private nz-bankaccount-weights
  {"A" [0 0 6 3 7 9 0 10 5 8 4 2 1 0 0 0]
   "B" [0 0 0 0 0 0 0 10 5 8 4 2 1 0 0 0]
   "D" [0 0 0 0 0 0 7 6 5 4 3 2 1 0 0 0]
   "E" [0 0 0 0 0 0 0 0 0 5 4 3 2 0 0 1]
   "F" [0 0 0 0 0 0 1 7 3 1 7 3 1 0 0 0]
   "G" [0 0 0 0 0 0 1 3 7 1 3 7 1 3 7 1]
   "X" [0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0]})
(def ^:private nz-bankaccount-moduli
  {"A" [11 11], "B" [11 11], "D" [11 11], "E" [9 11], "F" [10 10], "G" [9 10], "X" [1 1]})
(defn- nz-bankaccount-compact ^String [^String n]
  (let [n (str/replace n #"\D" "")]
    (str (subs n 0 (min 13 (count n)))
         (if (> (count n) 13) (clojure.core/format "%03d" (Long/parseLong (subs n 13))) ""))))
(defn- nz-bankaccount-checksum ^long [^String n]
  (let [algorithm (get nz-bankaccount-algorithms (subs n 0 2) "X")
        algorithm (if (and (= "A" algorithm) (not (neg? (compare (subs n 6 13) "0990000")))) "B" algorithm)
        [mod1 mod2] (get nz-bankaccount-moduli algorithm)
        weights (get nz-bankaccount-weights algorithm)]
    (mod (long (reduce + (map (fn [w d]
                                (let [c (* (long w) (long d))]
                                  (if (> c mod1) (mod c mod1) c)))
                              weights (digits-of n))))
         mod2)))
(defn- nz-bankaccount? [^String n]                    ; NZ bank account: checksum + known bank algorithm table; branch registry omitted
  (let [n (nz-bankaccount-compact n)]
    (and (re-matches #"\d{16}" n)
         (contains? nz-bankaccount-algorithms (subs n 0 2))
         (zero? (nz-bankaccount-checksum n)))))
(defn- kz-bin? [^String n]                            ; Kazakhstan BIN/IIN: 12-digit, weighted mod 11 with fallback weights
  (and (re-matches #"\d{12}" n)
       (let [d (digits-of n)
             r1 (mod (long (reduce + (map * (subvec d 0 11) [1 2 3 4 5 6 7 8 9 10 11]))) 11)
             c (if (not= r1 10) r1
                 (let [r2 (mod (long (reduce + (map * (subvec d 0 11) [3 4 5 6 7 8 9 10 11 1 2]))) 11)]
                   (when (not= r2 10) r2)))]
         (boolean (and c (= (long c) (d 11)))))))
(defn- upu-s10? [^String n]                           ; UPU S10 international postal item: 2 alpha + 8 serial + check + 2 country
  (and (re-matches #"[A-Z]{2}\d{9}[A-Z]{2}" n)
       (let [d (digits-of (subs n 2 10))
             r (mod (long (reduce + (map * d [8 6 4 2 3 5 9 7]))) 11)
             c (cond (= r 0) 5 (= r 1) 0 :else (- 11 r))]
         (= c (- (int (.charAt n 10)) 48)))))
(defn- si-maticna? [^String n]                        ; Slovenia matična številka: 7 or 10 digits, weighted mod 11
  (and (re-matches #"\d{7}(\d{3})?" n)
       (let [d (digits-of n)
             r (mod (- (long (reduce + (map * (subvec d 0 6) [7 6 5 4 3 2])))) 11)]
         (and (not= r 0) (= (mod r 10) (d 6))))))
(defn- iso11649? [^String n]                          ; ISO 11649 RF Creditor Reference: rearrange + ISO 7064 mod 97,10
  (and (re-matches #"RF\d{2}[0-9A-Z]{1,21}" n)
       (= 1 (lei-mod97 (str (subs n 4) (subs n 0 4))))))
(defn- it-aic? [^String n]                            ; Italy AIC (drug authorization code): 9 digits, leading 0, Luhn-variant mod 10
  (and (re-matches #"0\d{8}" n)
       (let [d (digits-of n)
             s (long (reduce + (map (fn [x w] (let [p (* (long x) (long w))] (+ (quot p 10) (rem p 10))))
                                    (subvec d 0 8) [1 2 1 2 1 2 1 2])))]
         (= (mod s 10) (d 8)))))
(defn- ca-bn? [^String n]                             ; Canada Business Number: 9-digit Luhn, optional 2-letter + 4-digit program account (BN15)
  (and (re-matches #"\d{9}([A-Z]{2}\d{4})?" n) (.isValid luhn-cd (subs n 0 9))))
(defn- md-idno? [^String n]                           ; Moldova IDNO: weighted mod 10
  (and (re-matches #"\d{13}" n)
       (let [d (digits-of n)
             c (mod (long (reduce + (map * (subvec d 0 12) [7 3 1 7 3 1 7 3 1 7 3 1]))) 10)]
         (= c (d 12)))))
(defn- lt-asmens? [^String n]                         ; Lithuania asmens kodas: Estonian-style mod-11 reweight + birth date
  (and (re-matches #"\d{11}" n)
       (let [d (digits-of n)
             g (long (d 0))
             century (case g 1 1800 2 1800 3 1900 4 1900 5 2000 6 2000 7 2100 8 2100 9 nil nil)
             year (when century (+ century (Integer/parseInt (subs n 1 3))))
             w (fn [ws] (mod (long (reduce + (map * (subvec d 0 10) ws))) 11))
             r (w [1 2 3 4 5 6 7 8 9 1])
             r (if (= r 10) (w [3 4 5 6 7 8 9 1 2 3]) r)]
         (and (or (= 9 g)
                  (and year
                       (valid-date? year (Integer/parseInt (subs n 3 5)) (Integer/parseInt (subs n 5 7)))))
              (= (if (= r 10) 0 r) (d 10))))))
(def ^:private ^String by-unp-letters "ABCEHKMOPT")
(def ^:private by-unp-first-chars (set "1234567ABCEHKM"))
(defn- by-unp? [^String n]                            ; Belarus UNP: digits or 2-letter prefix, weighted mod 11
  (let [n (strip-cc n "UNP")]
    (and (re-matches #"[0-9A-Z]{9}" n)
         (re-matches #"\d{7}" (subs n 2))
         (or (re-matches #"\d{2}" (subs n 0 2))
             (every? (set by-unp-letters) (subs n 0 2)))
         (contains? by-unp-first-chars (.charAt n 0))
         (let [s (if (re-matches #"\d{9}" n)
                   n
                   (str (.charAt n 0) (.indexOf by-unp-letters (int (.charAt n 1))) (subs n 2)))
               v (fn [^Character c] (Character/digit (char c) 36))
               c (mod (long (reduce + (map * [29 23 19 17 13 7 5 3] (map v (subs s 0 8))))) 11)]
           (and (<= c 9) (= c (Character/digit (.charAt n 8) 10)))))))
(defn- ua-rntrc? [^String n]                          ; Ukraine RNTRC: weighted mod 11 over first 9 digits
  (and (re-matches #"\d{10}" n)
       (let [d (digits-of n)]
         (= (mod (mod (long (reduce + (map * (subvec d 0 9) [-1 5 7 9 4 6 10 5 7]))) 11) 10)
            (d 9)))))
(defn- mu-nid? [^String n]                            ; Mauritius NID: letter + date/id + mod-17 check char
  (and (re-matches #"[A-Z]\d{12}[0-9A-Z]" n)
       (valid-date? (+ 2000 (Integer/parseInt (subs n 5 7)))
                    (Integer/parseInt (subs n 3 5))
                    (Integer/parseInt (subs n 1 3)))
       (let [check (reduce + (map-indexed
                              (fn [i ch] (* (- 14 (long i))
                                            (.indexOf m3736-alpha (int ch))))
                              (subs n 0 13)))
             check (mod (- 17 (long check)) 17)]
         (= (.charAt m3736-alpha (int check)) (.charAt n 13)))))
(defn- cu-ni? [^String n]                             ; Cuba NI: 11 digits with YYMMDD birth date
  (and (re-matches #"\d{11}" n)
       (let [century (cond
                       (= \9 (.charAt n 6)) 1800
                       (<= (int \0) (int (.charAt n 6)) (int \5)) 1900
                       :else 2000)
             year (+ century (Integer/parseInt (subs n 0 2)))]
         (valid-date? year (Integer/parseInt (subs n 2 4)) (Integer/parseInt (subs n 4 6))))))
(def ^:private ad-nrt-leading-letters #{\A \C \D \E \F \G \L \O \P \U})
(defn- ad-nrt? [^String n]                            ; Andorra NRT: type letter + 6 digits + trailing letter
  (and (re-matches #"[A-Z]\d{6}[A-Z]" n)
       (contains? ad-nrt-leading-letters (.charAt n 0))
       (let [digits (subs n 1 7)
             leading (.charAt n 0)]
         (and (or (not= \F leading) (not (pos? (compare digits "699999"))))
              (or (not (#{\A \L} leading))
                  (and (pos? (compare digits "699999"))
                       (neg? (compare digits "800000"))))))))

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
(defn- ch-esr-format [^String n]
  (str (subs n 0 2) " " (subs n 2 7) " " (subs n 7 12) " " (subs n 12 17) " "
       (subs n 17 22) " " (subs n 22 27)))
(defn- triple3-format [^String n] (str (subs n 0 3) " " (subs n 3 6) " " (subs n 6 9)))  ; SIN/TFN/org
(defn- hk-id-format [^String n]
  (let [k (dec (count n))] (str (subs n 0 k) "(" (subs n k) ")")))
(defn- kr-brn-format [^String n] (str (subs n 0 3) "-" (subs n 3 5) "-" (subs n 5 10)))
(defn- au-abn-format [^String n]
  (str (subs n 0 2) " " (subs n 2 5) " " (subs n 5 8) " " (subs n 8 11)))
(defn- fr-nir-format [^String n]
  (str (subs n 0 1) " " (subs n 1 3) " " (subs n 3 5) " " (subs n 5 7) " "
       (subs n 7 10) " " (subs n 10 13) " " (subs n 13 15)))
(defn- fi-ytunnus-format [^String n] (str (subs n 0 7) "-" (subs n 7 8)))
(defn- be-ogm-format [^String n]
  (let [n (be-ogm-compact n)]
    (str "+++" (subs n 0 3) "/" (subs n 3 7) "/" (subs n 7 12) "+++")))

(def ^:private registry
  {:credit-card {:validate card-valid? :parse card-parse :format card-format}
   :iban        {:validate iban-valid? :parse iban-parse :format iban-format}
   :bic         {:validate bic-valid? :parse bic-parse}
   :isbn        {:validate isbn-valid?}
   :issn        {:validate issn-valid? :format issn-hyphenate}
   :isin        {:validate isin-valid? :parse isin-parse}
   :aba         {:validate aba-valid?}
   :imei        {:validate imei-valid? :parse imei-parse}
   :imsi        {:validate imsi-valid? :parse imsi-parse}
   :mac         {:validate mac-valid? :parse mac-parse :format mac-format}
   :meid        {:validate meid-valid? :parse meid-parse}
   :luhn        {:validate luhn-valid?}
   :bitcoin     {:validate bitcoin-valid? :parse bitcoin-parse}
   :lei         {:validate lei-valid?}
   :cusip       {:validate cusip-valid?}
   :sedol       {:validate sedol-valid?}
   :de-wkn      {:validate de-wkn?}
   :cfi         {:validate cfi-valid? :parse cfi-parse}
   :eu-eic      {:validate eu-eic-valid? :parse eu-eic-parse}
   :eu-ecnumber {:validate eu-ecnumber?}
   :eu-banknote {:validate eu-banknote?}
   :isrc        {:validate isrc-valid? :parse isrc-parse :format isrc-format}
   :isil        {:validate isil-valid? :parse isil-parse}
   :br-cpf      {:validate cpf-valid? :format cpf-format}
   :br-cnpj     {:validate cnpj-valid? :format cnpj-format}
   :us-ssn      {:validate ssn-valid? :format ssn-format}
   :us-ein      {:validate ein-valid? :format ein-format}
   :us-itin     {:validate itin-valid? :parse taxpayer-parse :format ssn-format}
   :us-atin     {:validate atin-valid? :parse taxpayer-parse :format ssn-format}
   :us-ptin     {:validate ptin-valid?}
   :de-vat      {:validate de-vat?}
   :de-idnr     {:validate de-idnr?}
   :de-handelsregisternummer {:validate de-handelsregisternummer?}
   :de-stnr     {:validate de-stnr?}
   :fr-vat      {:validate fr-vat?}
   :mc-tva      {:validate mc-tva?}
   :it-vat      {:validate it-vat?}
   :be-vat      {:validate be-vat?}
   :pl-vat      {:validate pl-vat?}
   :gb-vat      {:validate gb-vat?}
   :gb-nino     {:validate nino? :format nino-format}
   :gb-utr      {:validate gb-utr?}
   :gb-upn      {:validate gb-upn?}
   :ca-sin      {:validate ca-sin? :format triple3-format}
   :au-abn      {:validate au-abn? :format au-abn-format}
   :in-pan      {:validate in-pan? :parse in-pan-parse}
   :in-aadhaar  {:validate in-aadhaar? :format aadhaar-format}
   :es-dni      {:validate es-dni?}
   :es-nie      {:validate es-nie?}
   :es-nif      {:validate es-nif?}
   :es-cae      {:validate es-cae?}
   :es-cups     {:validate es-cups?}
   :es-postalcode {:validate es-postalcode?}
   :es-referenciacatastral {:validate es-referenciacatastral?}
   :nl-bsn      {:validate nl-bsn?}
   :nl-brin     {:validate nl-brin?}
   :nl-identiteitskaartnummer {:validate nl-identiteitskaartnummer?}
   :nl-onderwijsnummer {:validate nl-onderwijsnummer?}
   :nl-postcode {:validate nl-postcode?}
   :cn-ric      {:validate cn-ric? :parse cn-ric-parse}
   :se-pnr      {:validate se-pnr? :parse se-pnr-parse :format se-pnr-format}
   :mx-clabe    {:validate mx-clabe? :parse mx-clabe-parse}
   :es-ccc      {:validate es-ccc? :parse es-ccc-parse}
   :za-id       {:validate za-id? :parse za-id-parse}
   :no-org      {:validate no-org? :format triple3-format}
   :no-mva      {:validate no-mva?}
   :no-kontonr  {:validate no-kontonr?}
   :no-fodselsnummer {:validate no-fodselsnummer?}
   :tr-tc       {:validate tr-tc?}
   :at-vat      {:validate at-vat?}
   :at-businessid {:validate at-businessid?}
   :at-postleitzahl {:validate at-postleitzahl?}
   :at-tin      {:validate at-tin?}
   :at-vnr      {:validate at-vnr?}
   :dk-vat      {:validate dk-vat?}
   :dk-cvr      {:validate dk-cvr?}
   :dk-cpr      {:validate dk-cpr?}
   :fi-vat      {:validate fi-vat?}
   :fi-ytunnus  {:validate fi-ytunnus? :format fi-ytunnus-format}
   :fi-associationid {:validate fi-associationid?}
   :fi-veronumero {:validate fi-veronumero?}
   :fo-vn       {:validate fo-vn?}
   :se-vat      {:validate se-vat?}
   :se-postnummer {:validate se-postnummer?}
   :gr-vat      {:validate gr-vat?}
   :pt-nif      {:validate pt-nif?}
   :pt-cc       {:validate pt-cc?}
   :cz-ico      {:validate cz-ico?}
   :cz-bankaccount {:validate cz-bankaccount?}
   :jp-cn       {:validate jp-cn?}
   :jp-in       {:validate jp-in?}
   :au-tfn      {:validate au-tfn? :format triple3-format}
   :lu-vat      {:validate lu-vat?}
   :si-vat      {:validate si-vat?}
   :in-gstin    {:validate in-gstin?}
   :ee-vat      {:validate ee-vat?}
   :hu-vat      {:validate hu-vat?}
   :hr-oib      {:validate hr-oib?}
   :it-cf       {:validate it-cf? :parse it-cf-parse}
   :ch-uid      {:validate ch-uid?}
   :ch-vat      {:validate ch-vat?}
   :ch-ahv      {:validate ch-ahv? :format ch-ahv-format}
   :ch-esr      {:validate ch-esr? :format ch-esr-format}
   :nz-ird      {:validate nz-ird?}
   :nz-bankaccount {:validate nz-bankaccount?}
   :be-nn       {:validate be-nn? :parse be-nn-parse :format be-nn-format}
   :be-bis      {:validate be-bis? :format be-nn-format}
   :be-ssn      {:validate be-ssn? :format be-nn-format}
   :be-eid      {:validate be-eid?}
   :be-ogm      {:validate be-ogm? :parse be-ogm-parse :format be-ogm-format}
   :fi-hetu     {:validate fi-hetu?}
   :is-vsk      {:validate is-vsk?}
   :figi        {:validate figi?}
   :mt-vat      {:validate mt-vat?}
   :sk-vat      {:validate sk-vat?}
   :lt-vat      {:validate lt-vat?}
   :cy-vat      {:validate cy-vat?}
   :ro-vat      {:validate ro-vat?}
   :ro-cf       {:validate ro-cf?}
   :ro-cui      {:validate ro-cui?}
   :ro-onrc     {:validate ro-onrc?}
   :es-vat      {:validate es-vat?}
   :ie-vat      {:validate ie-vat?}
   :nl-vat      {:validate nl-vat?}
   :lv-vat      {:validate lv-vat?}
   :bg-vat      {:validate bg-vat?}
   :hr-vat      {:validate hr-vat?}
   :cz-vat      {:validate cz-vat?}
   :pt-vat      {:validate pt-vat?}
   :iso6346     {:validate iso6346?}
   :ru-inn      {:validate ru-inn?}
   :tw-gui      {:validate tw-gui?}
   :ua-edrpou   {:validate ua-edrpou?}
   :ua-rntrc    {:validate ua-rntrc?}
   :cn-usci     {:validate cn-usci?}
   :iswc        {:validate iswc?}
   :is-kennitala {:validate is-kennitala?}
   :ve-rif      {:validate ve-rif?}
   :do-rnc      {:validate do-rnc?}
   :do-cedula   {:validate do-cedula?}
   :do-ncf      {:validate do-ncf?}
   :ru-ogrn     {:validate ru-ogrn?}
   :vn-mst      {:validate vn-mst?}
   :rs-pib      {:validate rs-pib?}
   :me-pib      {:validate me-pib?}
   :mk-edb      {:validate mk-edb?}
   :pl-regon    {:validate pl-regon?}
   :il-company  {:validate il-company?}
   :au-acn      {:validate au-acn?}
   :sk-ico      {:validate sk-ico?}
   :ee-rk       {:validate ee-rk?}
   :uy-rut      {:validate uy-rut?}
   :ec-ruc      {:validate ec-ruc?}
   :py-ruc      {:validate py-ruc?}
   :gt-nit      {:validate gt-nit?}
   :fr-siren    {:validate fr-siren?}
   :fr-siret    {:validate fr-siret?}
   :fr-nif      {:validate fr-nif?}
   :al-nipt     {:validate al-nipt?}
   :ar-dni      {:validate ar-dni?}
   :ca-bcphn    {:validate ca-bcphn?}
   :dz-nif      {:validate dz-nif?}
   :eg-tn       {:validate eg-tn?}
   :gh-tin      {:validate gh-tin?}
   :gn-nifp     {:validate gn-nifp?}
   :li-peid     {:validate li-peid?}
   :ma-ice      {:validate ma-ice?}
   :sm-coe      {:validate sm-coe?}
   :sv-nit      {:validate sv-nit?}
   :tn-mf       {:validate tn-mf?}
   :se-orgnr    {:validate se-orgnr?}
   :es-cif      {:validate es-cif?}
   :nz-nzbn     {:validate nz-nzbn?}
   :id-npwp     {:validate id-npwp?}
   :id-nik      {:validate id-nik?}
   :tr-vkn      {:validate tr-vkn?}
   :mx-rfc      {:validate mx-rfc?}
   :grid        {:validate grid?}
   :isan        {:validate isan?}
   :th-moa      {:validate th-moa?}
   :th-pin      {:validate th-pin?}
   :th-tin      {:validate th-tin?}
   :in-epic     {:validate in-epic?}
   :in-vid      {:validate in-vid?}
   :eu-at02     {:validate eu-at02?}
   :eu-nace     {:validate eu-nace?}
   :eu-oss      {:validate eu-oss?}
   :kz-bin      {:validate kz-bin?}
   :upu-s10     {:validate upu-s10?}
   :si-maticna  {:validate si-maticna?}
   :iso11649    {:validate iso11649?}
   :it-aic      {:validate it-aic?}
   :ca-bn       {:validate ca-bn?}
   :md-idno     {:validate md-idno?}
   :lt-asmens   {:validate lt-asmens?}
   :by-unp      {:validate by-unp?}
   :pk-cnic     {:validate pk-cnic?}
   :my-nric     {:validate my-nric?}
   :ke-pin      {:validate ke-pin?}
   :cu-ni       {:validate cu-ni?}
   :ad-nrt      {:validate ad-nrt?}
   :za-tin      {:validate za-tin?}
   :mu-nid      {:validate mu-nid?}
   :sg-nric     {:validate sg-nric?}
   :sg-uen      {:validate sg-uen?}
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
   :ar-cbu      {:validate ar-cbu? :parse ar-cbu-parse}
   :cl-rut      {:validate cl-rut? :format dash-check-format}
   :co-nit      {:validate co-nit? :format dash-check-format}
   :pe-ruc      {:validate pe-ruc? :parse pe-ruc-parse}
   :pe-cui      {:validate pe-cui?}
   :cr-cpf      {:validate cr-cpf?}
   :cr-cpj      {:validate cr-cpj?}
   :cr-cr       {:validate cr-cr?}
   :ie-pps      {:validate ie-pps?}
   :ee-ik       {:validate ee-ik? :parse ee-ik-parse}
   :jmbg        {:validate jmbg? :parse jmbg-parse}
   :si-emso     {:validate si-emso?}
   :ro-cnp      {:validate ro-cnp?}
   :cz-rc       {:validate cz-rc?}
   :sk-rc       {:validate sk-rc?}
   :kr-rrn      {:validate kr-rrn?}
   :gr-amka     {:validate gr-amka?}
   :il-idnr     {:validate il-idnr?}
   :ec-ced      {:validate ec-ced? :parse ec-ced-parse}
   :bg-egn      {:validate bg-egn? :parse bg-egn-parse}
   :bg-pnf      {:validate bg-pnf?}
   :orcid       {:validate orcid? :format orcid-format}
   :isni        {:validate orcid? :format isni-format}
   :gtin14      {:validate gtin14?}
   :sscc        {:validate sscc?}
   :gln         {:validate gln?}
   :mx-curp     {:validate mx-curp? :parse curp-parse}})

(def types
  "The set of identifier-type keywords this library understands."
  (set (keys registry)))

(def ^:private raw-input-types #{:bitcoin :cfi :isil :cz-bankaccount :de-handelsregisternummer})

(defn- entry ^clojure.lang.IPersistentMap [type]
  (or (registry type)
      (throw (IllegalArgumentException.
              (str "Unknown identifier type: " (pr-str type)
                   ". Known types: " (sort types))))))

(defn- input-for ^String [type s]
  (cond
    (= :eu-eic type) (if s (-> (str s) (str/replace #"\s" "") str/upper-case) "")
    (raw-input-types type) (if s (str s) "")
    :else (norm s)))

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
    (try (boolean (validate (input-for type s))) (catch Exception _ false))))

(defn parse
  "Validate `s` as `type` and return a map. On success: at least `{:valid? true}`,
  plus type-specific fields (e.g. card `:network`; IBAN `:country`/`:bban`/
  `:formatted`). On bad data: `{:valid? false}`. Unknown `type` throws."
  [type s]
  (let [{:keys [validate parse]} (entry type)
        n (input-for type s)]
    (if (try (boolean (validate n)) (catch Exception _ false))
      (if parse (try (parse n) (catch Exception _ {:valid? true})) {:valid? true})
      {:valid? false})))

(defn format
  "Canonical human-readable form of `s` as `type` (e.g. IBAN grouped in fours,
  ISSN hyphenated, card grouped in fours), or nil if `s` is not valid. Unknown
  `type` throws."
  [type s]
  (let [{:keys [validate format]} (entry type)
        n (input-for type s)]
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
