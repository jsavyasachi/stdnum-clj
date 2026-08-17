(ns stdnum.gs1-128
  "A parser for GS1-128 (formerly UCC/EAN-128) element strings. These strings use
  the Application Identifier (AI) encoding on logistics and retail barcodes.

  `parse` accepts the human-readable parenthesized form or the raw scanned form.
  In the raw form, FNC1 (the ASCII group separator 0x1D) ends a variable-length
  field. `parse` returns a vector of segment maps, in order:

      (parse \"(01)09521234543213(15)170331(10)ABC123\")
      ;=> [{:ai \"01\" :label \"GTIN\"        :value \"09521234543213\"}
      ;    {:ai \"15\" :label \"BEST BEFORE\" :value \"170331\"}
      ;    {:ai \"10\" :label \"BATCH/LOT\"   :value \"ABC123\"}]

  Weight, measure, and amount AIs carry an implied decimal place: the last digit
  of the AI. Those segments also report `:decimals` and a numeric
  `:decimal-value`."
  (:require [clojure.string :as str]))

(def ^:private fnc1 (char 29))

;; Fixed-length and variable-length AIs (common subset of the GS1 General
;; Specifications). `:len` = fixed data length; `:max` = variable, up to N chars.
(def ^:private ai-table
  {"00"  {:label "SSCC" :len 18}
   "01"  {:label "GTIN" :len 14}
   "02"  {:label "CONTENT (GTIN)" :len 14}
   "10"  {:label "BATCH/LOT" :max 20}
   "11"  {:label "PROD DATE" :len 6}
   "12"  {:label "DUE DATE" :len 6}
   "13"  {:label "PACK DATE" :len 6}
   "15"  {:label "BEST BEFORE" :len 6}
   "16"  {:label "SELL BY" :len 6}
   "17"  {:label "USE BY/EXPIRY" :len 6}
   "20"  {:label "VARIANT" :len 2}
   "21"  {:label "SERIAL" :max 20}
   "22"  {:label "CPV" :max 20}
   "240" {:label "ADDITIONAL ID" :max 30}
   "241" {:label "CUST. PART No." :max 30}
   "30"  {:label "VAR. COUNT" :max 8}
   "37"  {:label "COUNT" :max 8}
   "400" {:label "ORDER NUMBER" :max 30}
   "410" {:label "SHIP TO GLN" :len 13}
   "412" {:label "PURCHASE FROM GLN" :len 13}
   "414" {:label "LOC GLN" :len 13}
   "8005" {:label "PRICE PER UNIT" :len 6}
   "8018" {:label "GSRN" :len 18}})

;; Measure families: AI = 3-digit base + 1 decimal digit. Value is 6 digits.
(def ^:private measure-bases
  {"310" "NET WEIGHT (kg)" "311" "LENGTH (m)" "312" "WIDTH (m)" "313" "DEPTH (m)"
   "314" "AREA (m^2)" "315" "NET VOLUME (l)" "316" "NET VOLUME (m^3)"
   "330" "GROSS WEIGHT (kg)" "331" "LENGTH, GROSS (m)" "335" "GROSS VOLUME (l)"})

;; Amount families: AI = 3-digit base + 1 decimal digit; value is variable.
(def ^:private amount-bases
  {"390" "AMOUNT PAYABLE" "391" "AMOUNT PAYABLE (with ISO currency)"
   "392" "AMOUNT PAYABLE (single item)" "393" "AMOUNT PAYABLE (single, currency)"})

(defn- ai-spec
  "Resolve an AI string to {:label .. (:len N | :max N) (:decimals d)} or nil."
  [^String ai]
  (or (ai-table ai)
      (when (= 4 (count ai))
        (let [base (subs ai 0 3) dec (- (int (.charAt ai 3)) 48)]
          (cond
            (measure-bases base) {:label (measure-bases base) :len 6 :decimals dec}
            (amount-bases base)  {:label (amount-bases base) :max 15 :decimals dec})))))

(defn- with-decimals [seg {:keys [decimals]} ^String value]
  (if decimals
    (assoc seg :decimals decimals
           :decimal-value (/ (double (Long/parseLong value)) (Math/pow 10 decimals)))
    seg))

(defn- segment [^String ai spec ^String value]
  (with-decimals {:ai ai :label (:label spec "UNKNOWN") :value value} spec value))

(defn- valid-value? [spec ^String value]
  (and (pos? (count value))
       (not (str/includes? value (str fnc1)))
       (or (nil? (:decimals spec)) (re-matches #"\d+" value))
       (if-let [len (:len spec)]
         (= len (count value))
         (<= (count value) (:max spec)))))

(defn- parse-parens [^String s]
  (when (re-matches #"(?:\(\d{2,4}\)[^()]*)+" s)
    (let [segments (mapv (fn [[_ ai value]]
                           (when-let [spec (ai-spec ai)]
                             (when (valid-value? spec value)
                               (segment ai spec value))))
                         (re-seq #"\((\d{2,4})\)([^()]+)" s))]
      (when (and (seq segments) (every? some? segments))
        segments))))

(defn- ai-at [^String s i]
  (some (fn [k]
          (when (and (<= (+ i k) (count s))
                     (ai-spec (subs s i (+ i k))))
            (subs s i (+ i k))))
        [4 3 2]))

(declare parse-raw)

(defn- unseparated-ai-suffix? [^String s vstart]
  (some (fn [i]
          (when (and (ai-at s i) (parse-raw (subs s i)))
            true))
        (range (inc vstart) (count s))))

(defn- parse-raw [^String s]
  (loop [i 0 out []]
    (if (>= i (count s))
      out
      ;; longest AI prefix (4..2 digits) that resolves
      (let [ai (ai-at s i)]
        (if-not ai
          nil                                          ; unknown AI or unparsed tail
          (let [spec (ai-spec ai)
                vstart (+ i (count ai))
                fixed-len (:len spec)
                gs (str/index-of s fnc1 vstart)
                vend (if fixed-len
                       (+ vstart fixed-len)
                       (or gs (count s)))]
            (when (<= vend (count s))
              (let [value (subs s vstart vend)
                    variable? (contains? spec :max)
                    missing-separator? (and variable?
                                            (nil? gs)
                                            (unseparated-ai-suffix? s vstart))]
                (when (and (valid-value? spec value) (not missing-separator?))
                  ;; skip the FNC1 that ends a variable field
                  (recur (if (and variable? gs) (inc vend) vend)
                         (conj out (segment ai spec value))))))))))))

(defn parse
  "Parse a GS1-128 element string (parenthesized or raw/FNC1 form) into an ordered
  vector of `{:ai :label :value (:decimals :decimal-value)}` segments. Invalid
  data returns `{:valid? false}`."
  [s]
  (if (string? s)
    (or (if (str/includes? s "(") (parse-parens s) (parse-raw s))
        {:valid? false})
    {:valid? false}))

(defn valid?
  "True if `s` is a completely valid GS1-128 element string."
  [s]
  (vector? (parse s)))

(defn parse-map
  "Like `parse`, but returns a map of AI string -> value (last wins on repeats).
  Invalid data returns `{:valid? false}`."
  [s]
  (let [parsed (parse s)]
    (if (vector? parsed)
      (into {} (map (juxt :ai :value)) parsed)
      parsed)))
