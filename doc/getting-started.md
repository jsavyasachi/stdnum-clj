# Getting Started

`stdnum-clj` validates, parses, formats, and detects standard identifier numbers.

## Install

Leiningen / Boot:
```clojure
[net.clojars.savya/stdnum-clj "0.29.0"]
```

deps.edn:
```clojure
net.clojars.savya/stdnum-clj {:mvn/version "0.29.0"}
```

## Core API

```clojure
(require '[stdnum.core :as stdnum])
```

The main functions dispatch on an identifier-type keyword.

```clojure
(stdnum/valid? :iban "GB82 WEST 1234 5698 7654 32")
;;=> true

(stdnum/parse :credit-card "4111111111111111")
;;=> {:valid? true, :network :visa, :iin "411111", :last4 "1111"}

(stdnum/format :br-cnpj "11222333000181")
;;=> "11.222.333/0001-81"

(set (stdnum/detect "4111111111111111"))
;;=> #{:credit-card :luhn}
```

`card-network` is a shortcut for payment-card network detection.

```clojure
(stdnum/card-network "6011111111111117")
;;=> :discover
```

`compact` strips spaces, dots, hyphens, and slashes, then uppercases:

```clojure
(stdnum/compact "GB82 WEST 1234 5698 7654 32")
;;=> "GB82WEST12345698765432"
```

`stdnum/types` is the authoritative set of supported type keywords:

```clojure
(contains? stdnum/types :iban)
;;=> true
```

## Parse Results

`parse` always returns a map. On success, every result has at least
`{:valid? true}`. Types with extractable structure add type-specific keys:

```clojure
(select-keys (stdnum/parse :iban "GB82WEST12345698765432")
             [:valid? :country :bank-code :branch-code :account-number])
;;=> {:valid? true, :country "GB", :bank-code "WEST",
;;    :branch-code "123456", :account-number "98765432"}

(stdnum/parse :za-id "8001015009087")
;;=> {:valid? true, :birth-date "1980-01-01", :gender :male, :citizen true}
```

Types without extractable fields still return `{:valid? true}` on valid input,
for example `(stdnum/parse :aba "021000021")`.

## Error Contract

Bad data is data, not an exception:

```clojure
(stdnum/valid? :iban "GB82 WEST 1234 5698 7654 33")
;;=> false

(stdnum/parse :iban "GB82 WEST 1234 5698 7654 33")
;;=> {:valid? false}

(stdnum/format :iban "GB82 WEST 1234 5698 7654 33")
;;=> nil
```

Only an unknown identifier-type keyword throws `IllegalArgumentException`.

```clojure
(stdnum/valid? :not-a-type "x")
;; throws IllegalArgumentException
```

## First REPL Session

```clojure
(require '[stdnum.core :as stdnum])

(stdnum/valid? :credit-card "4111 1111 1111 1111")
;;=> true

(stdnum/card-network "4111111111111111")
;;=> :visa

(stdnum/parse :mx-curp "HEGG560427MVZRRL04")
;;=> {:valid? true, :birth-date "1956-04-27", :gender :female,
;;    :state "VZ", :state-name "Veracruz"}

(stdnum/format :orcid "0000000218250097")
;;=> "0000-0002-1825-0097"

(stdnum/detect "79927398713")
;;=> [:luhn]
```
