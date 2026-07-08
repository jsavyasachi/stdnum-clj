# Recipes

## Validating Payment Data

Validate a payment card, then read the card network and display-safe fields:

```clojure
(require '[stdnum.core :as stdnum])

(stdnum/valid? :credit-card "378282246310005")
;;=> true

(stdnum/parse :credit-card "378282246310005")
;;=> {:valid? true, :network :amex, :iin "378282", :last4 "0005"}

(stdnum/card-network "6011111111111117")
;;=> :discover
```

Parse an IBAN when you need fields that exist in its BBAN structure:

```clojure
(stdnum/parse :iban "GB82WEST12345698765432")
;;=> {:valid? true,
;;    :country "GB",
;;    :bban "WEST12345698765432",
;;    :formatted "GB82 WEST 1234 5698 7654 32",
;;    :bank-code "WEST",
;;    :branch-code "123456",
;;    :account-number "98765432"}
```

Validate BIC, ABA routing, and Mexico CLABE account numbers:

```clojure
(stdnum/parse :bic "DEUTDEFF500")
;;=> {:valid? true,
;;    :bank-code "DEUT",
;;    :country "DE",
;;    :location-code "FF",
;;    :branch-code "500"}

(stdnum/valid? :aba "021000021")
;;=> true

(stdnum/parse :mx-clabe "002010077777777771")
;;=> {:valid? true,
;;    :bank-code "002",
;;    :branch-code "010",
;;    :account "07777777777"}
```

## Parsing National IDs That Embed Data

Some national identifiers encode dates, gender, region, or other structured
fields. `parse` exposes those fields after the checksum and structure pass.

```clojure
(stdnum/parse :za-id "8001015009087")
;;=> {:valid? true,
;;    :birth-date "1980-01-01",
;;    :gender :male,
;;    :citizen true}

(stdnum/parse :mx-curp "HEGG560427MVZRRL04")
;;=> {:valid? true,
;;    :birth-date "1956-04-27",
;;    :gender :female,
;;    :state "VZ",
;;    :state-name "Veracruz"}

(stdnum/parse :pl-pesel "44051401359")
;;=> {:valid? true,
;;    :birth-date "1944-05-14",
;;    :gender :male}
```

Other parseable national IDs include Estonia `:ee-ik`, ex-Yugoslav `:jmbg`,
China `:cn-ric`, France `:fr-nir`, Sweden `:se-pnr`, Belgium `:be-nn`,
Bulgaria `:bg-egn`, India `:in-pan`, Ecuador `:ec-ced`, Peru `:pe-ruc`, and
Italy `:it-cf`.

## Which Type Is This?

Use `detect` when you have a value but not a type. The result is a vector because
some values satisfy more than one validator.

```clojure
(set (stdnum/detect "4111111111111111"))
;;=> #{:credit-card :luhn}

(stdnum/detect "GB82WEST12345698765432")
;;=> [:iban]

(stdnum/detect "nonsense")
;;=> []
```

## Raw Check-Digit Algorithms

Use `stdnum.checkdigit` when you need the algorithm directly instead of a typed
identifier validator.

```clojure
(require '[stdnum.checkdigit :as cd])

(cd/luhn-valid? "79927398713")
;;=> true

(cd/luhn-check-digit "7992739871")
;;=> "3"

(cd/verhoeff-valid? "234123412346")
;;=> true

(cd/verhoeff-check-digit "23412341234")
;;=> "6"

(cd/iso7064-mod11-2-valid? "0000000218250097")
;;=> true

(cd/iso7064-mod11-2-check "000000021825009")
;;=> "7"

(cd/iso7064-mod97-10-valid? "5493001KJTIIGC8Y1R12")
;;=> true
```

## Live EU VAT Registration Checks

`stdnum.core` can validate VAT number syntax and check digits. To ask whether an
EU VAT number is currently registered, use `stdnum.vies`:

```clojure
(require '[stdnum.vies :as vies])

(vies/check "LU26375245")
;;=> {:valid? true,
;;    :country "LU",
;;    :vat-number "26375245",
;;    :name "...",
;;    :address "...",
;;    :request-date "...",
;;    :raw {...}}
```

Network I/O lives only in `stdnum.vies`. This namespace requires JDK 11+ because
it uses `java.net.http`.

Member-state outages, rate limits, and service failures return an error map
instead of `false`:

```clojure
{:error "MS_UNAVAILABLE", :raw {...}}
```

That means validity is unknown for that lookup. It does not mean the VAT number
is invalid.

## Decoding GS1-128 Barcode Element Strings

`stdnum.gs1-128` parses GS1-128 Application Identifier element strings in either
parenthesized form or raw scanner form with FNC1 separators.

```clojure
(require '[stdnum.gs1-128 :as gs1])

(gs1/parse "(01)09521234543213(3103)000123(10)ABC123")
;;=> [{:ai "01", :label "GTIN", :value "09521234543213"}
;;    {:ai "3103", :label "NET WEIGHT (kg)", :value "000123",
;;     :decimals 3, :decimal-value 0.123}
;;    {:ai "10", :label "BATCH/LOT", :value "ABC123"}]

(gs1/parse-map "(01)09521234543213(10)ABC123")
;;=> {"01" "09521234543213", "10" "ABC123"}
```

Raw FNC1 form uses ASCII group separator character 29 after variable-length
fields:

```clojure
(def fnc1 (str (char 29)))

(gs1/parse (str "0109521234543213" "10ABC123" fnc1 "17170331"))
;;=> [{:ai "01", :label "GTIN", :value "09521234543213"}
;;    {:ai "10", :label "BATCH/LOT", :value "ABC123"}
;;    {:ai "17", :label "USE BY/EXPIRY", :value "170331"}]
```
