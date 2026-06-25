(ns stdnum.checkdigit
  "Standalone check-digit algorithms (Luhn, Verhoeff, ISO 7064), usable directly
  when you need the primitive rather than a typed identifier validator.

  Each algorithm offers a `*-valid?` predicate over a complete string (payload +
  trailing check character) and, where meaningful, a `*-check-digit` /
  `*-check` calculator that returns the check character for a bare payload.

      (luhn-valid? \"79927398713\")          ;=> true
      (luhn-check-digit \"7992739871\")       ;=> \"3\"
      (iso7064-mod11-2-check \"000000021825009\") ;=> \"7\"   (ORCID)"
  (:import [org.apache.commons.validator.routines.checkdigit
            LuhnCheckDigit VerhoeffCheckDigit]))

(def ^:private ^LuhnCheckDigit luhn-cd (LuhnCheckDigit.))
(def ^:private ^VerhoeffCheckDigit verhoeff-cd (VerhoeffCheckDigit.))

(defn- digits? [^String s] (and (string? s) (boolean (re-matches #"\d+" s))))

;; --- Luhn (mod 10): credit cards, IMEI, many national numbers -----------------
(defn luhn-valid?
  "True if the digit string `s` (payload plus its trailing check digit) satisfies
  the Luhn checksum. Non-digit input returns false rather than throwing."
  [s]
  (boolean (and (digits? s) (.isValid luhn-cd ^String s))))

(defn luhn-check-digit
  "The Luhn check digit (a one-character string) for the bare payload digit
  string `payload`."
  [^String payload]
  (.calculate luhn-cd payload))

;; --- Verhoeff: India Aadhaar and others ---------------------------------------
(defn verhoeff-valid?
  "True if the digit string `s` (payload plus check digit) satisfies the Verhoeff
  checksum. Non-digit input returns false."
  [s]
  (boolean (and (digits? s) (.isValid verhoeff-cd ^String s))))

(defn verhoeff-check-digit
  "The Verhoeff check digit (a one-character string) for `payload`."
  [^String payload]
  (.calculate verhoeff-cd payload))

;; --- ISO 7064 Mod 11-2: ORCID, ISNI, ISBN-10 (check char may be X) ------------
(defn iso7064-mod11-2-check
  "The ISO 7064 Mod 11-2 check character for the digit string `payload`, as
  \"0\"-\"9\" or \"X\"."
  [^String payload]
  (let [t (reduce (fn [^long acc c] (* (+ acc (- (int c) 48)) 2)) 0 payload)
        r (mod (- 12 (mod (long t) 11)) 11)]
    (if (= r 10) "X" (str r))))

(defn iso7064-mod11-2-valid?
  "True if `s` (digits plus a final \"0\"-\"9\"/\"X\" check character) is valid
  under ISO 7064 Mod 11-2."
  [^String s]
  (boolean (and (string? s) (re-matches #"\d+[0-9X]" s)
                (= (iso7064-mod11-2-check (subs s 0 (dec (count s))))
                   (subs s (dec (count s)))))))

;; --- ISO 7064 Mod 97-10 over alphanumerics: LEI, the IBAN family --------------
(defn- mod97-10-remainder ^long [^String s]
  (reduce (fn [^long acc c]
            (let [d (if (Character/isDigit ^char c) (- (int c) 48) (+ 10 (- (int c) 65)))]
              (mod (+ (* acc (if (>= d 10) 100 10)) d) 97)))
          0 s))

(defn iso7064-mod97-10-valid?
  "True if the alphanumeric string `s` (letters A-Z taken as 10-35, check digits
  included) is valid under ISO 7064 Mod 97-10 - i.e. its running remainder is 1.
  This is the LEI / IBAN-style checksum."
  [s]
  (boolean (and (string? s) (re-matches #"[0-9A-Z]+" s) (= 1 (mod97-10-remainder s)))))
