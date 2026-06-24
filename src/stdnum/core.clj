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
            LuhnCheckDigit ABANumberCheckDigit]
           [org.iban4j Iban Bic]))

(defn- norm ^String [s]
  (if s (-> (str s) (str/replace #"[\s.\-]" "") str/upper-case) ""))

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

(def ^:private registry
  {:credit-card {:validate card-valid? :parse card-parse :format card-format}
   :iban        {:validate iban-valid? :parse iban-parse :format iban-format}
   :bic         {:validate bic-valid?}
   :isbn        {:validate isbn-valid?}
   :issn        {:validate issn-valid? :format issn-hyphenate}
   :isin        {:validate isin-valid?}
   :aba         {:validate aba-valid?}
   :imei        {:validate imei-valid?}
   :luhn        {:validate luhn-valid?}})

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
