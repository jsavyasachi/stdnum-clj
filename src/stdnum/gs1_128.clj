(ns stdnum.gs1-128
  "Parsing of GS1-128 (formerly UCC/EAN-128) element strings - the Application
  Identifier (AI) encoding used on logistics and retail barcodes.

  `parse` accepts either the human-readable parenthesized form or the raw scanned
  form (variable-length fields terminated by FNC1 / ASCII group-separator 0x1D),
  and returns a vector of segment maps in order:

      (parse \"(01)09521234543213(15)170331(10)ABC123\")
      ;=> [{:ai \"01\" :label \"GTIN\"        :value \"09521234543213\"}
      ;    {:ai \"15\" :label \"BEST BEFORE\" :value \"170331\"}
      ;    {:ai \"10\" :label \"BATCH/LOT\"   :value \"ABC123\"}]

  Weight/measure and amount AIs carry an implied decimal place (the AI's last
  digit); those segments also report `:decimals` and a numeric `:decimal-value`."
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

(defn- parse-parens [^String s]
  (mapv (fn [[_ ai value]] (segment ai (ai-spec ai) value))
        (re-seq #"\((\d{2,4})\)([^(]*)" s)))

(defn- parse-raw [^String s]
  (loop [i 0 out []]
    (if (>= i (count s))
      out
      ;; longest AI prefix (4..2 digits) that resolves
      (let [ai (some (fn [k] (when (and (<= (+ i k) (count s))
                                        (ai-spec (subs s i (+ i k))))
                               (subs s i (+ i k))))
                     [4 3 2])]
        (if-not ai
          out                                          ; unknown AI: stop, return what we have
          (let [spec (ai-spec ai)
                vstart (+ i (count ai))
                vend (if-let [len (:len spec)]
                       (min (count s) (+ vstart len))
                       (let [gs (str/index-of s fnc1 vstart)] (or gs (count s))))
                value (subs s vstart vend)
                ;; skip a trailing FNC1 after a variable field
                next-i (if (and (:max spec) (< vend (count s)) (= 29 (int (.charAt s vend))))
                         (inc vend) vend)]
            (recur next-i (conj out (segment ai spec value)))))))))

(defn parse
  "Parse a GS1-128 element string (parenthesized or raw/FNC1 form) into an ordered
  vector of `{:ai :label :value (:decimals :decimal-value)}` segments."
  [^String s]
  (if (str/includes? s "(") (parse-parens s) (parse-raw s)))

(defn parse-map
  "Like `parse`, but returns a map of AI string -> value (last wins on repeats)."
  [^String s]
  (into {} (map (juxt :ai :value)) (parse s)))
