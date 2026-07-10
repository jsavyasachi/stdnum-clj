# stdnum-clj

[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.savya/stdnum-clj.svg)](https://clojars.org/net.clojars.savya/stdnum-clj)
[![cljdoc](https://cljdoc.org/badge/net.clojars.savya/stdnum-clj)](https://cljdoc.org/d/net.clojars.savya/stdnum-clj)
[![test](https://github.com/jsavyasachi/stdnum-clj/actions/workflows/test.yml/badge.svg)](https://github.com/jsavyasachi/stdnum-clj/actions/workflows/test.yml)

Unified validation, parsing, and formatting of 240+ standard identifier numbers for Clojure -
IBAN/BIC, credit cards, ISBN/ISSN/ISIN, and national ID, VAT/GST, and tax numbers for 80+
countries - behind one small API.

## Stack

<a href="https://clojure.org"><img src="https://img.shields.io/badge/Clojure-5881D8?style=flat&logo=clojure&logoColor=white" alt="Clojure" /></a>
<a href="https://commons.apache.org/proper/commons-validator/"><img src="https://img.shields.io/badge/Commons%20Validator-D22128?style=flat&logo=apache&logoColor=white" alt="Apache Commons Validator" /></a>
<a href="https://github.com/arturmkrtchyan/iban4j"><img src="https://img.shields.io/badge/iban4j-2D3748?style=flat&logo=java&logoColor=white" alt="iban4j" /></a>

> Unofficial, community-maintained. Not affiliated with Apache, iban4j, or any card network.

## Why

Clojure has plenty of *one-identifier* libraries (an IBAN parser here, a Luhn checker there),
most of them tiny and unmaintained, each with its own API. There was no single library that
validates the common, checksummable identifiers under one consistent interface - the way
Python's [python-stdnum](https://arthurdejong.org/python-stdnum/) does. `stdnum-clj` is that
facade, and now covers python-stdnum's full catalogue of number formats. For the international
identifiers it
wraps the maintained [Apache Commons Validator](https://commons.apache.org/proper/commons-validator/)
and [iban4j](https://github.com/arturmkrtchyan/iban4j) engines rather than reinventing them, so
those checks are as correct as those libraries and stay correct as they're updated. The national
and tax standards with public, well-documented algorithms are implemented clean-room and kept
under this library's EPL license.

## Install

Leiningen / Boot:

```clojure
[net.clojars.savya/stdnum-clj "0.28.0"]
```

deps.edn:

```clojure
net.clojars.savya/stdnum-clj {:mvn/version "0.28.0"}
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
(stdnum/parse :credit-card "378282246310005")   ;=> {:valid? true, :network :amex, :iin "378282", :last4 "0005"}
(stdnum/parse :iban "GB82WEST12345698765432")
;=> {:valid? true, :country "GB", :bban "WEST12345698765432",
;    :bank-code "WEST", :branch-code "123456", :account-number "98765432", :formatted "GB82 WEST ..."}

;; some national IDs embed structured data - parse pulls it out
(stdnum/parse :mx-curp "HEGG560427MVZRRL04")
;=> {:valid? true, :birth-date "1956-04-27", :gender :female, :state "VZ", :state-name "Veracruz"}
(stdnum/parse :za-id "8001015009087")
;=> {:valid? true, :gender :male, :citizen true, :birth-date "1980-01-01"}

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

## Check-digit primitives

When you need the raw algorithm rather than a typed validator, `stdnum.checkdigit` exposes them
directly:

```clojure
(require '[stdnum.checkdigit :as cd])
(cd/luhn-valid? "79927398713")            ;=> true
(cd/luhn-check-digit "7992739871")        ;=> "3"
(cd/verhoeff-check-digit "23412341234")   ;=> "6"
(cd/iso7064-mod11-2-check "000000021825009") ;=> "7"  (ORCID/ISNI check char, may be "X")
(cd/iso7064-mod97-10-valid? "5493001KJTIIGC8Y1R12") ;=> true  (LEI / IBAN family)
```

## GS1-128 barcode parsing

`stdnum.gs1-128` decodes GS1-128 (UCC/EAN-128) Application Identifier element strings - the data
carried on logistics and retail barcodes - in either the parenthesized or raw FNC1 form:

```clojure
(require '[stdnum.gs1-128 :as gs1])
(gs1/parse "(01)09521234543213(3103)000123(10)ABC123")
;=> [{:ai "01"   :label "GTIN"           :value "09521234543213"}
;    {:ai "3103" :label "NET WEIGHT (kg)" :value "000123" :decimals 3 :decimal-value 0.123}
;    {:ai "10"   :label "BATCH/LOT"      :value "ABC123"}]

(gs1/parse-map "(01)09521234543213(10)ABC123")  ;=> {"01" "09521234543213", "10" "ABC123"}
```

## Online VAT validation (VIES)

A checksum proves a VAT number is *well-formed*; it can't prove the company exists. `stdnum.vies`
checks a number against the EU's live [VIES](https://ec.europa.eu/taxation_customs/vies/) registry:

```clojure
(require '[stdnum.vies :as vies])
(vies/check "LU26375245")
;=> {:valid? true, :country "LU", :vat-number "26375245",
;    :name "AMAZON EUROPE CORE S.A R.L.", :address "38, AVENUE JOHN F. KENNEDY...", ...}
```

A member-state outage (`MS_UNAVAILABLE`, rate-limiting, …) returns `{:error "..."}` rather than a
misleading `:valid? false` - validity is genuinely unknown when the registry can't answer. This is
the only part of the library that does network I/O; it lives in its own namespace, **requires JDK
11+** (uses `java.net.http`), and pulls in `org.clojure/data.json`. `stdnum.core` stays pure.

## Supported identifiers

`stdnum/types` is the authoritative set. National identifiers are keyed by an ISO-3166 prefix
(`:br-cpf`, `:us-ssn`, `:de-vat`); full descriptions are on [cljdoc](https://cljdoc.org/d/net.clojars.savya/stdnum-clj).

<details>
<summary><b>All 246 types, by category</b></summary>

| Category | Types |
|----------|-------|
| **Banking & cards** | `:credit-card` (+ network) · `:iban` · `:bic` · `:aba` · `:mx-clabe` · `:cz-bankaccount` · `:nz-bankaccount` · `:iso11649` · `:ar-cbu` · `:es-ccc` · `:be-ogm` · `:ch-esr` · `:no-kontonr` · `:eu-at02` |
| **Securities** | `:isin` · `:lei` · `:cusip` · `:sedol` · `:de-wkn` · `:figi` · `:cfi` |
| **Publishing / media / device** | `:isbn` · `:issn` · `:ismn` · `:iswc` · `:grid` · `:isan` · `:eu-banknote` · `:imei` · `:luhn` · `:isrc` · `:isil` · `:mac` · `:imsi` · `:meid` · `:bitcoin` |
| **Commerce / logistics / vehicle / industry** | `:ean13` · `:ean8` · `:upc` · `:gtin14` · `:sscc` · `:gln` · `:iso6346` · `:upu-s10` · `:vin` · `:imo` · `:cas` · `:nhs` · `:npi` · `:it-aic` · `:eu-eic` · `:eu-ecnumber` · `:eu-nace` · `:es-cae` · `:es-cups` · `:es-postalcode` · `:at-postleitzahl` · `:nl-brin` · `:nl-postcode` · `:se-postnummer` |
| **Research / name** | `:orcid` · `:isni` |
| **National & tax IDs — Europe** | `:gb-nino` · `:es-dni` · `:es-nie` · `:es-nif` · `:es-referenciacatastral` · `:nl-bsn` · `:nl-identiteitskaartnummer` · `:nl-onderwijsnummer` · `:se-pnr` · `:no-org` · `:no-fodselsnummer` · `:pt-nif` · `:pt-cc` · `:cz-ico` · `:hr-oib` · `:it-cf` · `:ch-uid` · `:ch-ahv` · `:be-nn` · `:be-bis` · `:be-ssn` · `:be-eid` · `:fi-hetu` · `:fr-nir` · `:fr-nif` · `:pl-pesel` · `:ie-pps` · `:ee-ik` · `:lt-asmens` · `:si-emso` · `:ro-cnp` · `:ro-cf` · `:ro-cui` · `:ro-onrc` · `:cz-rc` · `:sk-rc` · `:gr-amka` · `:bg-egn` · `:bg-pnf` · `:ru-inn` · `:ua-edrpou` · `:ua-rntrc` · `:is-kennitala` · `:ru-ogrn` · `:rs-pib` · `:me-pib` · `:mk-edb` · `:pl-regon` · `:sk-ico` · `:ee-rk` · `:fr-siren` · `:fr-siret` · `:se-orgnr` · `:es-cif` · `:md-idno` · `:by-unp` · `:si-maticna` · `:ad-nrt` · `:al-nipt` · `:li-peid` · `:sm-coe` · `:gb-utr` · `:gb-upn` · `:dk-cvr` · `:dk-cpr` · `:fi-ytunnus` · `:fi-associationid` · `:fi-veronumero` · `:de-idnr` · `:de-handelsregisternummer` · `:de-stnr` · `:at-businessid` · `:at-tin` · `:at-vnr` · `:jmbg` |
| **National & tax IDs — Americas** | `:us-ssn` · `:us-ein` · `:br-cpf` · `:br-cnpj` · `:ca-sin` · `:ca-bcphn` · `:ar-cuit` · `:ar-dni` · `:cl-rut` · `:co-nit` · `:pe-ruc` · `:pe-cui` · `:cr-cpf` · `:cr-cpj` · `:cr-cr` · `:ec-ced` · `:mx-curp` · `:ve-rif` · `:do-rnc` · `:do-cedula` · `:do-ncf` · `:uy-rut` · `:ec-ruc` · `:py-ruc` · `:gt-nit` · `:mx-rfc` · `:ca-bn` · `:cu-ni` · `:sv-nit` · `:us-itin` · `:us-atin` · `:us-ptin` |
| **National & tax IDs — Asia-Pacific** | `:au-abn` · `:au-tfn` · `:in-pan` · `:in-aadhaar` · `:in-epic` · `:in-vid` · `:cn-ric` · `:jp-cn` · `:jp-in` · `:nz-ird` · `:sg-nric` · `:sg-uen` · `:hk-id` · `:kr-brn` · `:kr-rrn` · `:tw-gui` · `:cn-usci` · `:vn-mst` · `:au-acn` · `:nz-nzbn` · `:id-npwp` · `:id-nik` · `:th-moa` · `:th-pin` · `:th-tin` · `:kz-bin` · `:my-nric` · `:pk-cnic` |
| **National & tax IDs — Africa & M. East** | `:za-id` · `:za-tin` · `:tr-tc` · `:il-idnr` · `:il-company` · `:tr-vkn` · `:mu-nid` · `:ke-pin` · `:dz-nif` · `:eg-tn` · `:gh-tin` · `:gn-nifp` · `:ma-ice` · `:tn-mf` |
| **VAT / GST** (EU-27 complete) | `:de-vat` · `:fr-vat` · `:mc-tva` · `:it-vat` · `:be-vat` · `:pl-vat` · `:gb-vat` · `:at-vat` · `:dk-vat` · `:fi-vat` · `:se-vat` · `:gr-vat` · `:lu-vat` · `:si-vat` · `:ee-vat` · `:hu-vat` · `:mt-vat` · `:sk-vat` · `:lt-vat` · `:cy-vat` · `:ro-vat` · `:es-vat` · `:ie-vat` · `:nl-vat` · `:lv-vat` · `:bg-vat` · `:hr-vat` · `:cz-vat` · `:pt-vat` · `:in-gstin` · `:eu-oss` · `:ch-vat` · `:no-mva` · `:fo-vn` · `:is-vsk` |

</details>

International identifiers are wrapped from Commons Validator / iban4j; global and national
standards with public, well-documented algorithms (LEI, VAT, CPF/CNPJ, SSN, …) are implemented
clean-room and stay under this library's EPL license. More are added on demand - open an issue
for an identifier you need.

## Verification (source of truth)

Correctness is pinned by a cited corpus, not ad-hoc assertions. `test/stdnum/vectors.edn` maps
every one of the identifier types to `{:valid [...] :invalid [...] :source "..."}`, and `:source`
is mandatory. Each vector is a worked example from the standard, a government registry, an issuing
company's published number, a [python-stdnum](https://arthurdejong.org/python-stdnum/) module
doctest, or a live VIES check. The test suite is driven from this file, so adding a format means
adding a cited vector first.

Every checksummed type is verified against an independently recomputed check digit. Where a type is
purely structural (validated by format, embedded date, or component code, with no check digit) the
`:source` says so. Where no published example exists, a number constructed from the published
algorithm is cited as *constructed* rather than passed off as a real published number.

For EU VAT, entries tagged `:vies true` are confirmed live-registered companies, and
`lein test :integration` re-checks them against the official VIES service. (A useful distinction
that surfaced: a valid checksum doesn't imply a *registered* number - several common example VAT
numbers are checksum-valid but unregistered, and are labelled as algorithm examples accordingly.)

## License

Copyright © 2026 Savyasachi

Distributed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/).
