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
Python's `python-stdnum` does. `stdnum-clj` is that facade. It does **not** reimplement the
algorithms: it wraps the maintained [Apache Commons Validator](https://commons.apache.org/proper/commons-validator/)
and [iban4j](https://github.com/arturmkrtchyan/iban4j) engines, so the checks are as correct
as those libraries and stay correct as they're updated.

## Install

Leiningen / Boot:

```clojure
[net.clojars.savya/stdnum-clj "0.1.0"]
```

deps.edn:

```clojure
net.clojars.savya/stdnum-clj {:mvn/version "0.1.0"}
```

## Usage

```clojure
(require '[stdnum.core :as stdnum])

;; valid? - dispatch on an identifier-type keyword
(stdnum/valid? :iban "GB82 WEST 1234 5698 7654 32")  ;=> true
(stdnum/valid? :credit-card "4111 1111 1111 1111")   ;=> true (spaces/hyphens tolerated)
(stdnum/valid? :isbn "978-0-306-40615-7")            ;=> true
(stdnum/valid? :iban "GB82 WEST 1234 5698 7654 33")  ;=> false (bad check digit)

;; parse - validity plus the useful extracted fields
(stdnum/parse :credit-card "378282246310005")
;=> {:valid? true, :network :amex}
(stdnum/parse :iban "GB82WEST12345698765432")
;=> {:valid? true, :country "GB", :bban "WEST12345698765432",
;    :formatted "GB82 WEST 1234 5698 7654 32"}
(stdnum/parse :isin "US0378331004")
;=> {:valid? false}

;; format - canonical human form (nil if invalid)
(stdnum/format :iban "GB82WEST12345698765432")  ;=> "GB82 WEST 1234 5698 7654 32"
(stdnum/format :credit-card "4111111111111111")  ;=> "4111 1111 1111 1111"

;; detect - which types consider this value valid
(stdnum/detect "4111111111111111")  ;=> [:credit-card :luhn]
(stdnum/detect "nonsense")          ;=> []

;; convenience
(stdnum/card-network "6011111111111117")  ;=> :discover
stdnum/types  ;=> #{:credit-card :iban :bic :isbn :issn :isin :aba :imei :luhn}
```

`valid?`, `parse`, and `format` throw `IllegalArgumentException` only on an **unknown
identifier type** (a programming bug). Bad *data* never throws: `valid?` returns `false`,
`parse` returns `{:valid? false}`, `format` returns `nil`.

## Supported identifiers

| Type | Meaning | Engine |
|------|---------|--------|
| `:credit-card` | Card number + network (Visa/Mastercard/Amex/Discover/Diners) | Commons Validator |
| `:iban` | International Bank Account Number (+ country/BBAN) | iban4j |
| `:bic` | Bank Identifier Code (SWIFT) | iban4j |
| `:isbn` | ISBN-10 and ISBN-13 | Commons Validator |
| `:issn` | International Standard Serial Number | Commons Validator |
| `:isin` | International Securities Identification Number | Commons Validator |
| `:aba` | US bank routing number (ABA) | Commons Validator |
| `:imei` | Mobile device IMEI (Luhn over 15 digits) | Commons Validator |
| `:luhn` | Raw Luhn (mod-10) check | Commons Validator |

National identifier formats (per-country tax/VAT/company IDs, à la `python-stdnum`) are
intentionally out of scope for this core artifact; they are a possible future companion.

## License

Copyright (c) 2026 Savyasachi. Released under the
[Eclipse Public License 1.0](https://www.eclipse.org/legal/epl-v10.html).
