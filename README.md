# stdnum-clj

Unified validation, parsing, and formatting of standard identifier numbers for Clojure -
credit cards, IBAN/BIC, ISBN, ISSN, ISIN, US bank routing (ABA), IMEI, and the raw Luhn
check, behind one small API.

## Stack

<a href="https://clojure.org"><img src="https://img.shields.io/badge/Clojure-5881D8?style=flat&logo=clojure&logoColor=white" alt="Clojure" /></a>
<a href="https://commons.apache.org/proper/commons-validator/"><img src="https://img.shields.io/badge/Commons%20Validator-D22128?style=flat&logo=apache&logoColor=white" alt="Apache Commons Validator" /></a>
<a href="https://github.com/arturmkrtchyan/iban4j"><img src="https://img.shields.io/badge/iban4j-2D3748?style=flat&logo=java&logoColor=white" alt="iban4j" /></a>

[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.savya/stdnum-clj.svg)](https://clojars.org/net.clojars.savya/stdnum-clj)
[![cljdoc](https://cljdoc.org/badge/net.clojars.savya/stdnum-clj)](https://cljdoc.org/d/net.clojars.savya/stdnum-clj)

> Unofficial, community-maintained. Not affiliated with Apache, iban4j, or any card network.

## Why

Clojure has plenty of *one-identifier* libraries (an IBAN parser here, a Luhn checker there),
most of them tiny and unmaintained, each with its own API. There was no single library that
validates the common, checksummable identifiers under one consistent interface - the way
Python's `python-stdnum` does. `stdnum-clj` is that facade. For the international identifiers it
wraps the maintained [Apache Commons Validator](https://commons.apache.org/proper/commons-validator/)
and [iban4j](https://github.com/arturmkrtchyan/iban4j) engines rather than reinventing them, so
those checks are as correct as those libraries and stay correct as they're updated. A few global
and national standards with public, well-documented algorithms (LEI, Brazil CPF/CNPJ) are
implemented clean-room and kept under this library's EPL license.

## Install

Leiningen / Boot:

```clojure
[net.clojars.savya/stdnum-clj "0.4.0"]
```

deps.edn:

```clojure
net.clojars.savya/stdnum-clj {:mvn/version "0.4.0"}
```

## Usage

```clojure
(require '[stdnum.core :as stdnum])

;; valid? - dispatch on an identifier-type keyword (any of `stdnum/types`)
(stdnum/valid? :iban        "GB82 WEST 1234 5698 7654 32")  ;=> true
(stdnum/valid? :credit-card "4111 1111 1111 1111")          ;=> true  (separators tolerated)
(stdnum/valid? :de-vat      "DE136695976")                  ;=> true  (country prefix optional)
(stdnum/valid? :iban        "GB82 WEST 1234 5698 7654 33")  ;=> false (bad check digit)

;; parse - validity plus extracted fields where they exist
(stdnum/parse :credit-card "378282246310005")   ;=> {:valid? true, :network :amex}
(stdnum/parse :iban "GB82WEST12345698765432")
;=> {:valid? true, :country "GB", :bban "WEST12345698765432", :formatted "GB82 WEST ..."}

;; format - canonical human form, or nil if invalid
(stdnum/format :br-cnpj "11222333000181")  ;=> "11.222.333/0001-81"

;; detect - which types consider a value valid
(stdnum/detect "4111111111111111")  ;=> [:credit-card :luhn]

;; helpers
(stdnum/card-network "6011111111111117")  ;=> :discover
stdnum/types                              ;=> #{:iban :credit-card :de-vat ...} (the full set)
```

`valid?`, `parse`, and `format` throw `IllegalArgumentException` only on an **unknown
identifier type** (a programming bug). Bad *data* never throws: `valid?` returns `false`,
`parse` returns `{:valid? false}`, `format` returns `nil`.

## Supported identifiers

`stdnum/types` is the authoritative set. National identifiers are keyed by an ISO-3166 prefix
(`:br-cpf`, `:us-ssn`, `:de-vat`); full descriptions are on [cljdoc](https://cljdoc.org/d/net.clojars.savya/stdnum-clj).

<details>
<summary><b>All 40 types, by category</b></summary>

| Category | Types |
|----------|-------|
| **Banking & cards** | `:credit-card` (+ network) · `:iban` · `:bic` · `:aba` · `:mx-clabe` |
| **Securities** | `:isin` · `:lei` · `:cusip` · `:sedol` |
| **Publishing / device** | `:isbn` · `:issn` · `:imei` · `:luhn` |
| **Tax & national IDs** | `:us-ssn` · `:us-ein` · `:gb-nino` · `:br-cpf` · `:br-cnpj` · `:ca-sin` · `:au-abn` · `:in-pan` · `:in-aadhaar` · `:es-dni` · `:es-nie` · `:nl-bsn` · `:cn-ric` · `:se-pnr` · `:za-id` · `:no-org` · `:tr-tc` |
| **VAT** | `:de-vat` · `:fr-vat` · `:it-vat` · `:be-vat` · `:pl-vat` · `:gb-vat` · `:at-vat` · `:dk-vat` · `:fi-vat` · `:se-vat` |

</details>

International identifiers are wrapped from Commons Validator / iban4j; global and national
standards with public, well-documented algorithms (LEI, VAT, CPF/CNPJ, SSN, …) are implemented
clean-room and stay under this library's EPL license. More are added on demand - open an issue
for an identifier you need.

## License

Copyright (c) 2026 Savyasachi. Released under the
[Eclipse Public License 1.0](https://www.eclipse.org/legal/epl-v10.html).
