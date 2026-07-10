# Changelog

All notable changes to this project are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- `:ru-snils` — Russian individual insurance account number (mod-101 check),
  ported from python-stdnum PR #495.

### Fixed
- `:vatin` / `:eu-vat` now reject a duplicated country-code prefix
  (e.g. `FRFR40303265045`), matching python-stdnum issue #420.
- `:ec-ruc` no longer rejects juridical RUCs (3rd digit `9`) whose check digit
  is not computable. Per Ecuador's SRI, such numbers can only be validated
  against the registry, so 3rd-digit-9 RUCs are now accepted on structure
  (valid province + establishment ≠ `000`). Fixes false negatives on active
  companies (python-stdnum issue #497).

## [0.29.0] - 2026-07-10

### Added
- Ten identifier types closing the coverage gaps the parity watcher flagged
  against python-stdnum master (246 -> 256):
  - `:az-voen` (Azerbaijan VÖEN), `:de-leitweg` (Germany Leitweg-ID),
    `:eu-excise` (EU Excise/SEED), `:fr-accise` (France excise),
    `:fr-rcs` (France RCS), `:mz-nuit` (Mozambique NUIT),
    `:sn-ninea` (Senegal NINEA).
  - `:us-tin` — valid under any of `:us-ssn`/`:us-itin`/`:us-ein`/`:us-ptin`/`:us-atin`.
  - `:vatin` — generic VAT id dispatching on the country-code prefix to the
    matching `:<cc>-vat` validator (EL→gr, XI→gb); `:eu-vat` gates the same
    dispatch to EU member states.

### Fixed
- `:es-cae` now accepts the `F7` (biodiésel/biometanol fiscal deposits) and `GP`
  (gasóleo profesional) activity keys from Orden EHA/3482/2007 Anexo XLII, which
  were previously rejected as invalid (python-stdnum PR #493).

## [0.28.0] - 2026-07-10

89 new identifier types (157 -> 246), reaching full coverage of the
python-stdnum catalogue. Every checksummed type was verified against an
independently recomputed published/doctest number; structural and
data-file-backed types are validated by format and cited as such. The eleven
groups below land the new types.

### Added - Germany, Asia, EU remainder — full python-stdnum parity (234 -> 246)
Twelve final types ported from python-stdnum, completing coverage of the
upstream catalogue (aliases and postal/classification filler included).
- Checksummed: `:in-epic` (India EPIC, Luhn), `:in-vid` (India VID, Verhoeff),
  `:jp-in` (Japan My Number), `:pe-cui` (Peru), `:eu-at02` (SEPA creditor id,
  ISO 7064 Mod 97-10), `:nz-bankaccount` (per-bank checksum), `:cz-bankaccount`
  (dual mod-11; takes raw input since the prefix `-` and bank `/` separators are
  significant).
- Reuse: `:th-tin` dispatches to `:th-pin` or `:th-moa`.
- Structural: `:de-handelsregisternummer`, `:de-stnr`, `:eu-nace`, `:eu-oss`
  (court/region/classification tables validated by format, not embedded lists).

### Added - misc national IDs and tax numbers (221 -> 234)
Thirteen more types ported from python-stdnum.
- Checksummed: `:ca-bcphn` (BC health number, mod 11), `:gh-tin` (Ghana),
  `:gn-nifp` (Guinea, Luhn), `:ma-ice` (Morocco, ISO 7064 Mod 97-10),
  `:sv-nit` (El Salvador).
- Structural: `:al-nipt` (Albania), `:ar-dni` (Argentina), `:dz-nif` (Algeria),
  `:eg-tn` (Egypt), `:tn-mf` (Tunisia), `:li-peid` (Liechtenstein),
  `:sm-coe` (San Marino), `:do-ncf` (Dominican fiscal receipt).

### Added - Romania, Costa Rica, Belgium, Bulgaria (211 -> 221)
Ten more types ported from python-stdnum.
- `:ro-cf` / `:ro-cui` — Romania fiscal / unique registration code, mod-11 CUI
  check (`:ro-cf` also accepts a 13-digit CNP, per upstream).
- `:ro-onrc` — Romania trade register number, structural + new-format check.
- `:cr-cpf` / `:cr-cpj` / `:cr-cr` — Costa Rica physical / legal / resident ids
  (structural).
- `:be-bis` — Belgium BIS number, mod-97 check with month offset.
- `:be-ssn` — Belgium SSN, accepting a national number or a BIS number.
- `:be-eid` — Belgium eID card number, mod-97 check.
- `:bg-pnf` — Bulgaria foreigner number, weighted mod-10 check.

### Added - Netherlands + Nordic cluster (201 -> 211)
Ten more types ported from python-stdnum.
- `:nl-brin` — Netherlands institution id (structural).
- `:nl-identiteitskaartnummer` — Netherlands ID-card number (structural; upstream
  has no checksum, only bans the letter O).
- `:nl-onderwijsnummer` — Netherlands education number, BSN-style 11-test whose
  remainder must equal 5 and which must start with `10`.
- `:nl-postcode` — Netherlands postal code, 4 digits + 2 letters (banned pairs).
- `:fi-associationid` — Finland association registry id (structural).
- `:fi-veronumero` — Finland tax number (structural; upstream has no check digit).
- `:fo-vn` — Faroe Islands VAT (structural).
- `:no-kontonr` — Norway bank account, 11-digit weighted mod 11 or 7-digit Luhn.
- `:se-postnummer` — Sweden postal code (structural).
- `:is-vsk` — Iceland VAT (structural).

### Added - Spain + Austria cluster (193 -> 201)
Eight more types ported from python-stdnum.
- `:es-cae` — Spain excise activity code (structural).
- `:es-cups` — Spain electricity/gas supply-point code, two check letters
  (`int(body) % 529` split into two base-23 digits).
- `:es-postalcode` — Spain postal code, province 01-52 (structural).
- `:es-referenciacatastral` — Spain cadastral reference, dual check letters.
- `:at-businessid` — Austria company register number (structural).
- `:at-postleitzahl` — Austria postal code. Validated structurally (4-digit,
  1000-9999); upstream checks against a bundled data file, but a partial
  hardcoded list would reject valid codes, so this port stays structural.
- `:at-tin` — Austria tax identification number, weighted check digit.
- `:at-vnr` — Austria social-insurance number, weighted check digit.

### Added - Thailand PIN, Spain NIF, and structural IDs (187 -> 193)
Six more types ported from python-stdnum.
- `:th-pin` — Thailand Personal Identification Number, 13-digit weighted mod 11.
- `:es-nif` — Spain NIF, dispatching over the existing DNI / NIE / CIF checks.
- `:sg-uen` — Singapore Unique Entity Number, three structural formats.
- `:cu-ni` — Cuba national identifier, embedded-date structural validation.
- `:ad-nrt` — Andorra NRT, leading category letter + 6 digits + trailing letter.
- `:de-wkn` — Germany WKN securities id, 6-char alphabet excluding `I`/`O`.

### Added - structural national IDs (181 -> 187)
Six more types ported from python-stdnum. These validate primarily by format,
embedded date, and component-code ranges/tables (most have no check digit), so
their corpus vectors are cited as structural; `:za-tin` does carry a Luhn check.
- `:dk-cpr` — Denmark CPR, date plus century-from-serial (the mod-11 check was
  retired in 2007, so it is not enforced, matching python-stdnum).
- `:pk-cnic` — Pakistan CNIC, province (1-7) and gender-digit components.
- `:my-nric` — Malaysia NRIC, embedded date plus the fixed birthplace-code set.
- `:id-nik` — Indonesia NIK, province/regency codes plus DDMMYY with the +40
  day offset for females.
- `:ke-pin` — Kenya PIN, `[AP]` + 9 digits + trailing letter.
- `:za-tin` — South Africa tax reference, leading-digit constraint plus Luhn.

### Added - more checksummed tax/personal IDs (175 -> 181)
Six more types ported from python-stdnum; every vector is the module's own
doctest number with its checksum independently recomputed and each validator
confirmed a faithful port of the upstream algorithm.
- `:do-cedula` — Dominican Republic cédula, 11-digit Luhn.
- `:md-idno` — Moldova company IDNO, weighted mod 10 (weights 7-3-1 repeating).
- `:lt-asmens` — Lithuania personal code, reusing the Estonian `ee.ik` mod-11
  reweight plus embedded-date validation.
- `:by-unp` — Belarus payer number, radix-36 weighted mod 11 handling both the
  all-digit and the two-letter-prefixed forms.
- `:me-pib` — Montenegro tax number, weighted mod 11.
- `:mk-edb` — North Macedonia tax number, weighted mod 11 (optional `MK` prefix).

### Added - VAT completions and EU/misc checksummed (169 -> 175)
Six more types ported from python-stdnum; every vector is the module's own
doctest number with its checksum independently recomputed.
- `:no-mva` — Norway VAT: the `:no-org` organisasjonsnummer plus the `MVA`
  suffix.
- `:mc-tva` — Monaco VAT: the French TVA key, with the mandatory `000`
  component that distinguishes Monaco from ordinary French numbers.
- `:eu-ecnumber` — EC number (European Community chemical substance number),
  weighted mod 11.
- `:eu-banknote` — Euro banknote serial, letter-as-ASCII digit sum mod 9.
- `:pt-cc` — Portugal Cartão de Cidadão, ISO 7064-style radix-36 Luhn check.
- `:ua-rntrc` — Ukraine individual taxpayer number (РНОКПП), weighted mod 11.

### Added - more national IDs and tax/VAT (163 -> 169)
Six further types ported from python-stdnum; each checksum recomputed against the
module's own doctest vector (Mauritius NID has no upstream doctest, so its vector
is constructed from the published check-letter algorithm).
- `:no-fodselsnummer` — Norway birth number, two mod-11 control digits plus the
  individual-number century table and date.
- `:si-emso` — Slovenia EMŠO, the Slovenian JMBG (shares the `:jmbg` mod-11
  check) with an added date validation.
- `:mu-nid` — Mauritius National Identity number, ISO 7064 Mod 37,36 radix-36
  check letter.
- `:fr-nif` — France numéro fiscal, `first-10-digits mod 511 == last 3`.
- `:ch-vat` — Switzerland VAT (`CHE` + UID + MWST/TVA/IVA/TPV suffix), reusing
  the `:ch-uid` mod-11 check.
- `:gb-upn` — UK Unique Pupil Number, leading mod-23 check letter over the
  12-digit body with LA-code validation.

### Added - national personal-ID numbers (157 -> 163)
Six national identity numbers, ported from python-stdnum with each checksum
independently recomputed against the module's own test vector.
- `:ro-cnp` — Romania Cod Numeric Personal, weighted mod 11 (`10 -> 1`); embedded
  YYMMDD date and county code validated.
- `:cz-rc` / `:sk-rc` — Czech / Slovak rodné číslo (shared Czechoslovak algorithm):
  9-digit pre-1954 form (date only) and 10-digit mod-11 form.
- `:kr-rrn` — South Korea Resident Registration Number, weighted mod 11
  (`(11 - r) mod 10`); birth date and region code validated.
- `:gr-amka` — Greece AMKA social-security number, Luhn over 11 digits with an
  embedded DDMMYY date.
- `:il-idnr` — Israel identity number, Luhn over the 9-digit zero-padded form.

## [0.27.1] - 2026-07-08

### Added
- Added cljdoc guide articles: Getting Started, Recipes, and Adding an Identifier Type.

## [0.27.0] - 2026-07-07

19 new types (138 -> 157), in three groups. Every checksum verified against an
independently recomputed published number, per the corpus rules.

### Added - tech & media identifiers
- `:mac` — IEEE MAC-48/EUI-48 in all four written forms; `parse` exposes OUI,
  locally-administered and multicast bits; canonical colon `format`.
- `:bitcoin` — Bitcoin addresses: Base58Check (P2PKH/P2SH, double-SHA-256) and
  BIP-173 bech32 SegWit (mixed case rejected); `parse` exposes encoding/type.
- `:isrc` — ISO 3901 recording code, hyphenated `format` (IFPI database vector).
- `:imsi` — ITU E.212 subscriber identity, structural; `parse` exposes MCC.
- `:meid` — 3GPP2 mobile equipment identifier, base-16 Luhn check digit
  (TIA worked example); `parse` splits regional code/manufacturer/serial.
- `:isil` — ISO 15511 library identifier, structural.
- `:cfi` — ISO 10962 financial-instrument classification; `parse` maps the
  category letter to a keyword.

### Added - tax & business registries
- `:us-itin`, `:us-atin`, `:us-ptin` — IRS ITIN (group ranges 50-65/70-88/
  90-92/94-99), ATIN (group 93), PTIN (P + 8 digits).
- `:gb-utr` — HMRC Unique Taxpayer Reference, leading check digit
  (weights 6…2, lookup table); optional display "K" suffix stripped.
- `:dk-cvr` — Danish CVR company number, mod-11 (vector: Carlsberg A/S).
- `:fi-ytunnus` — Finnish Business ID; remainder-1 numbers rejected as never
  issued (vectors: Nokia Oyj, Kone Oyj); hyphenated `format`.
- `:de-idnr` — German tax IdNr, ISO 7064 MOD 11,10 plus the
  one-repeated-digit structural rule.

### Added - fintech payment formats
- `:ar-cbu` — Argentine CBU, dual 9713-pattern check digits; `parse` exposes
  bank/branch/account.
- `:es-ccc` — Spanish Código Cuenta Cliente, dual weighted mod-11 check pair
  (vector: the ISO 13616 Spanish IBAN worked example).
- `:eu-eic` — ENTSO-E Energy Identification Code, base-37 weighted check
  character.
- `:be-ogm` — Belgian structured payment reference (+++xxx/xxxx/xxxxx+++),
  mod 97 with 0 → 97; decorated `format`.
- `:ch-esr` — Swiss ESR/QR reference number, "modulo 10 recursive" check
  digit (SIX Annex B), grouped display `format`.

Deferred (no published algorithm or no verifiable real vector yet): `:sg-uen`
(ACRA does not publish the check-letter algorithm), `:es-cups`,
Czech/New-Zealand domestic bank account formats.

## [0.26.0] - 2026-06-27

### Added
- `:si-maticna` — Slovenia matična številka (company registration), weighted mod 11
  (vector: Krka d.d. Novo mesto, AJPES).
- `:iso11649` — ISO 11649 RF Creditor Reference for structured payments (ISO 7064
  Mod 97,10; standard worked example).
- `:it-aic` — Italy AIC pharmaceutical authorization code (Luhn-variant mod 10; vectors:
  Tachipirina / Angelini, AIFA registry).
- `:ca-bn` — Canada Business Number, 9-digit Luhn with optional BN15 program account
  (vector: Canadian Red Cross Society, CRA charity registry).

## [0.25.0] - 2026-06-27

### Added
- `:id-npwp` — Indonesia NPWP, classic 15-digit tax number (Luhn over the first 9;
  vector: PT Telkom Indonesia, IDX annual report).
- `:tr-vkn` — Turkey VKN (vergi kimlik numarası), 10-digit entity tax number (weighted
  mod-9 algorithm; vector: Türk Hava Yolları / Turkish Airlines, ETBİS).
- `:mx-rfc` — Mexico RFC, company (12-char) and person (13-char) taxpayer registry
  (SAT mod-11 check over the value table; vector: Petróleos Mexicanos / PEMEX, SAT CIF).
- `:upu-s10` — UPU S10 international postal item identifier.
- `:kz-bin` — Kazakhstan Business Identification Number.
- `:th-moa` — Thailand company tax ID.
- `:grid` — GRid (Global Release Identifier), 18-char music release ID (ISO 7064
  Mod 37,36; vector: the GRid standard worked example).
- `:isan` — ISAN (ISO 15706), audiovisual work identifier with two ISO 7064 Mod 37,36
  check characters (root and version; vectors: ISAN standard worked examples).

## [0.24.1] - 2026-06-26

### Added
- `:fr-siren` — France SIREN, 9-digit company identifier (Luhn; vectors: Renault S.A. /
  TotalEnergies SE, annuaire-entreprises.data.gouv.fr).
- `:fr-siret` — France SIRET, 14-digit establishment identifier (SIREN + 5-digit NIC, Luhn;
  vector: Renault S.A. siège social).
- `:se-orgnr` — Sweden organisationsnummer, 10-digit (Luhn, 3rd digit ≥ 2; vectors: Volvo AB /
  Ericsson AB, Bolagsverket).
- `:es-cif` — Spain CIF, org-letter + 7 digits + control digit or letter (vector: Banco
  Santander S.A., CNMV official register).
- `:nz-nzbn` — New Zealand Business Number, 13-digit GS1 GLN with `9429` prefix (mod-10;
  vectors: Air New Zealand Ltd / Fonterra Co-operative Group Ltd, nzbn.govt.nz).

### Changed
- Relicense from EPL 1.0 to **EPL 2.0** (no code change; corrects the published POM
  license metadata, which lagged the source).

## [0.24.0] - 2026-06-26

### Added
- `:fr-siren` — France SIREN, 9-digit company identifier (Luhn; vectors: Renault S.A. /
  TotalEnergies SE, annuaire-entreprises.data.gouv.fr).
- `:fr-siret` — France SIRET, 14-digit establishment identifier (SIREN + 5-digit NIC, Luhn;
  vector: Renault S.A. siège social).
- `:se-orgnr` — Sweden organisationsnummer, 10-digit (Luhn, 3rd digit ≥ 2; vectors: Volvo AB /
  Ericsson AB, Bolagsverket).
- `:es-cif` — Spain CIF, org-letter + 7 digits + control digit or letter (vector: Banco
  Santander S.A., CNMV official register).
- `:nz-nzbn` — New Zealand Business Number, 13-digit GS1 GLN with `9429` prefix (mod-10;
  vectors: Air New Zealand Ltd / Fonterra Co-operative Group Ltd, nzbn.govt.nz).

## [0.23.0] - 2026-06-26

### Added
- `:ee-rk` — Estonia registry code (registrikood), 8-digit (mod-11 with weight-set fallback;
  vectors: Tallink Grupp / Telia Eesti, official e-Äriregister).
- `:uy-rut` — Uruguay RUT tax number, 12-digit (weighted mod 11; vector: ANTEL, state telecom).
- `:ec-ruc` — Ecuador RUC, 13-digit, all three taxpayer classes (natural / public entity /
  juridical, selected by the 3rd digit; vectors: Banco Pichincha juridical + SRI public entity).
- `:py-ruc` — Paraguay RUC tax number (base + check digit, weighted mod 11; vector: PETROPAR).
- `:gt-nit` — Guatemala NIT tax number (base + check digit or `K`, weighted mod 11; vector:
  Cementos Progreso S.A.).

## [0.22.0] - 2026-06-25

### Added
- `:au-acn` — Australia Company Number, 9-digit (weights 8..1, complement mod 10; vector: BHP Group Ltd).
- `:sk-ico` — Slovakia IČO, 8-digit (same Czechoslovak mod-11 algorithm as `:cz-ico`; vector: ESET spol. s r.o.).

### Changed
- README install snippets bumped to the current release (were stale at 0.18.0).

## [0.21.0] - 2026-06-25

### Added
- `:il-company` — Israel company/ID number, 9-digit (Israeli Luhn; vector: Teva Pharmaceutical
  Industries Ltd, public registrar number).

## [0.20.0] - 2026-06-25

### Added
- `:rs-pib` — Serbia tax number (PIB; ISO 7064 MOD 11,10; vector: NIS a.d. Novi Sad, public).
- `:pl-regon` — Poland REGON statistical number, 9-digit (weighted mod 11; vector: PKN Orlen S.A.).

## [0.19.0] - 2026-06-25

### Added
- `:ru-ogrn` — Russia OGRN company registration number (check = first-12 mod 11, mod 10;
  vectors: Sberbank / Gazprom public EGRUL numbers).
- `:vn-mst` — Vietnam tax code / MST (10-digit, optional 3-digit branch suffix; weighted mod 11;
  vectors: Vietcombank / Vinamilk, public listed companies).

## [0.18.0] - 2026-06-24

### Added
- `:do-rnc` — Dominican Republic RNC tax number (vectors: 3M Dominicana, from the DGII's own
  published list, and Claro).

## [0.17.0] - 2026-06-24

### Added
- `:iswc` — International Standard Musical Work Code (ISO 15707; vector: the CISAC worked
  example `T-034.524.680-1`).
- `:is-kennitala` — Iceland kennitala (vector: Icelandair ehf's public registry number).
- `:ve-rif` — Venezuela RIF tax number (letter-prefixed; vector: PDVSA's public number).

## [0.16.0] - 2026-06-24

### Added
- `:ua-edrpou` — Ukraine EDRPOU company registration number (both weight-set branches;
  vectors: Naftogaz and Ukrzaliznytsia public numbers).
- `:cn-usci` — China Unified Social Credit Identifier, the 18-character company code
  (mod-31 over the 31-symbol alphabet; vector: Tencent's public number).

## [0.15.0] - 2026-06-24

### Added
- `:iso6346` — ISO 6346 freight container (BIC) number, with the standard's character-value
  weighting and mod-11 check (vector: the standard's worked example `CSQU3054383`).
- `:ru-inn` — Russia INN tax number (10-digit legal entity and 12-digit individual variants;
  vector: Gazprom PJSC's public number).
- `:tw-gui` — Taiwan Unified Business Number / 統一編號 (vector: TSMC's public number).

## [0.14.0] - 2026-06-24

EU-27 VAT coverage is now complete (100 identifier types).

### Added
- VAT validators for the six remaining EU member states: `:nl-vat` (Netherlands, 9-digit mod-11
  + `B` suffix), `:lv-vat` (Latvia PVN, legal entity), `:bg-vat` (Bulgaria, 9-digit EIK/BULSTAT
  or 10-digit EGN), `:hr-vat` (Croatia, `HR` + OIB), plus `:cz-vat` and `:pt-vat` aliases over
  the existing IČO / NIF checks. Every new vector is a real company number confirmed live against
  VIES (Booking.com, Air Baltic, Sopharma, Škoda Auto, EDP), `:vies true`.

## [0.13.0] - 2026-06-24

### Changed
- Six EU VAT verification vectors (AT, SE, GR, SI, EE, HU) upgraded from checksum-valid but
  unregistered example numbers to numbers confirmed live-registered against VIES (TU Wien,
  Björn Lundén AB, OTE, Postojnska Jama, Movek Grupp, Richter Gedeon), each tagged `:vies true`
  so `lein test :integration` re-checks them against the official registry.

### Fixed
- Removed an auto-boxing warning in `stdnum.gs1-128/parse-raw` (the FNC1 offset accumulator was
  Object-typed); the build is now reflection- and boxing-warning clean again.

## [0.12.0] - 2026-06-24

GS1-128 barcode parsing, and a cited verification corpus that drives the test suite (with live VIES
re-confirmation of registered VAT numbers).

### Added
- New namespace `stdnum.gs1-128` for parsing GS1-128 (UCC/EAN-128) Application Identifier element
  strings. `parse` handles both the parenthesized
  human-readable form and the raw FNC1-delimited scan form, returning ordered
  `{:ai :label :value}` segments; weight/measure and amount AIs also report `:decimals` and a
  numeric `:decimal-value`. `parse-map` returns an AI-keyed map.

### Changed
- Verification is now driven by a cited corpus (`test/stdnum/vectors.edn`): every type maps to
  valid/invalid vectors with a mandatory `:source`. EU VAT entries confirmed live-registered are
  re-checked against the official VIES service under `lein test :integration`.

## [0.11.0] - 2026-06-24

Two major capability additions plus broader format coverage and two new VAT types (92 -> 94):
standalone check-digit algorithms and online VAT validation.

### Added
- More canonical `format`: `:hk-id` (`A123456(3)`), `:kr-brn` (`124-81-00998`), `:au-abn`
  (`51 824 753 556`), `:au-tfn` (`123 456 782`), `:no-org` (`974 760 673`).
- New namespace `stdnum.checkdigit` exposing the underlying algorithms as standalone primitives:
  `luhn-valid?` /
  `luhn-check-digit`, `verhoeff-valid?` / `verhoeff-check-digit`, `iso7064-mod11-2-valid?` /
  `iso7064-mod11-2-check`, and `iso7064-mod97-10-valid?`.
- Two more VAT types (90 -> ... 94 total): `:es-vat` (Spain - covers the DNI, NIE and CIF forms;
  CIF accepts the digit- or letter-control convention) and `:ie-vat` (Ireland, mod-23 check letter).
  Verified against Telefónica's CIF and Google Ireland's VAT.
- New namespace `stdnum.vies` for **online** EU VAT validation against the official VIES service
  (`vies/check`), confirming a VAT number actually exists in the member-state registry and returning
  the trader name/address where disclosed. Member-state errors (e.g. `MS_UNAVAILABLE`) surface as
  `{:error ...}` so a transient outage is never mistaken for an invalid number. Requires JDK 11+
  (uses `java.net.http`); adds an `org.clojure/data.json` dependency. `stdnum.core` remains pure and
  network-free.

## [0.10.0] - 2026-06-24

Depth pass, part 2: broader `format` coverage and `parse` decomposition of structural identifiers
(BIC, CLABE, IMEI, ISIN) and non-date IDs. Still 92 types; this release deepens the surface so most
identifiers now report their parts, not just `{:valid? true}`. 22 types have `parse` extraction and
22 have canonical `format`.

### Added
- Canonical `format` for more existing types with standard display forms: `:nhs` (`943 476 5919`),
  `:gb-nino` (`AB 12 34 56 C`), `:in-aadhaar` (`2341 2341 2346`), `:ch-ahv` (`756.9217.0769.85`),
  `:ca-sin` (`046 454 286`), `:fr-nir` (`2 55 08 14 168 025 38`).
- More non-date `parse` extraction: `:in-pan` -> `:holder-type` (from the 4th character), `:ec-ced`
  -> `:province-code` `:province`, `:pe-ruc` -> `:entity-type` (`:company`/`:natural-person`), and
  `:credit-card` now also returns `:iin` (first 6) and `:last4`.
- Structural `parse` decomposition: `:bic` -> `:bank-code` `:country` `:location-code`
  `:branch-code` (via iban4j); `:mx-clabe` -> `:bank-code` `:branch-code` `:account`; `:imei` ->
  `:tac` `:serial`; `:isin` -> `:country` `:nsin`.

## [0.9.0] - 2026-06-24

Depth pass continued: more `parse` field-extraction and canonical `format`. Extraction now covers
every shipped identifier that embeds a birth date. No new types (still 92); this release deepens
the existing surface.

### Added
- `parse :cn-ric` (China resident ID) now extracts `:birth-date` (full YYYY-MM-DD) and `:gender`.
- More `parse` extraction: `:pl-pesel` -> `:birth-date` (century resolved from the PESEL month
  offset, so unambiguous) + `:gender`; `:fr-nir` -> `:gender` `:birth-year` `:birth-month`
  `:department`; `:se-pnr` -> `:birth-date` `:gender`; `:be-nn` -> `:birth-date` (century from the
  validating mod-97 base) + `:gender`; `:bg-egn` -> `:birth-date` (century from month offset) +
  `:gender`.
- Canonical `format` for `:se-pnr` (`811218-9876`) and `:be-nn` (`00.01.25-111.48`).
- Canonical `format` for more types: `:orcid` (`0000-0002-1825-0097`), `:isni`
  (`0000 0001 2103 2683`), `:cas` (`7732-18-5`), `:ar-cuit` (`30-70308853-4`), `:cl-rut`
  (`97.004.000-5`), `:co-nit` (`890.903.938-8`).

## [0.8.0] - 2026-06-24

Two new types (90 -> 92) plus a depth pass: `parse` now extracts the structured data embedded in
several identifiers. Everything verified against published example numbers; stays EPL.

### Added
- `:gln` (GS1 Global Location Number) and `:mx-curp` (Mexico CURP). Clean-room / engine-backed,
  each verified against a published example.
- `parse` now extracts the structured data embedded in several IDs (not just `{:valid? true}`):
  - `:mx-curp` -> `:birth-date` `:gender` `:state` `:state-name`
  - `:ee-ik` (Estonia), `:jmbg` (ex-Yugoslav) -> `:birth-date` `:gender` (+ `:region` for JMBG)
  - `:za-id` (South Africa) -> `:gender` `:citizen` `:birth-date`
  - `:vin` -> `:wmi` `:vds` `:vis` `:model-year` `:plant` `:serial`
  - `:it-cf` (Italy codice fiscale) -> `:gender` `:birth-day` `:birth-month` `:birth-year`
    `:comune-code` (the century is not encoded in a CF, so the 2-digit year is returned as-is)
  - `:iban` -> now also `:bank-code` `:branch-code` `:account-number` (where the country's BBAN
    defines them), via iban4j.

## [0.7.0] - 2026-06-24

Coverage expansion: 71 -> 90 identifier types. Adds LatAm tax IDs (Nubank/MercadoLibre audience),
more EU national/person IDs, research IDs (ORCID/ISNI), and the longer GS1 keys. Every new checksum
is clean-room from the public standard (or engine-backed) and verified against a real published
number.

### Added
- More commerce / industry identifiers: `:ean8` (EAN-8/GTIN-8), `:ismn` (sheet-music ISMN),
  `:cas` (CAS Registry Number for chemicals), `:imo` (IMO ship number). Clean-room / engine-backed,
  each verified against a real published number.
- National tax / person IDs: `:fr-nir` (France social security), `:pl-pesel` (Poland), `:ar-cuit`
  (Argentina), `:cl-rut` (Chile), `:co-nit` (Colombia). Clean-room, each verified against a real
  published number.
- More national IDs: `:pe-ruc` (Peru), `:ie-pps` (Ireland PPS), `:ee-ik` (Estonia isikukood),
  `:jmbg` (shared ex-Yugoslav number: RS/BA/ME/MK/SI/HR), `:ec-ced` (Ecuador cedula). Clean-room,
  each verified against a real published number.
- `:bg-egn` (Bulgaria), `:orcid` and `:isni` (researcher / name IDs, ISO 7064 MOD 11-2), and the
  longer GS1 keys `:gtin14` and `:sscc`. Clean-room, each verified against a real published number.

## [0.6.0] - 2026-06-24

Coverage expansion: 54 -> 71 identifier types, adding a commerce/vehicle/health category and
APAC business IDs. Every new checksum is clean-room from the public standard (or engine-backed)
and verified against a real published number.

### Added
- `:nz-ird` (New Zealand IRD), `:be-nn` (Belgium national number), `:fi-hetu` (Finland HETU).
  Clean-room, verified against published numbers.
- `:figi` (Financial Instrument Global Identifier, OMG check digit) and four more EU VAT numbers:
  `:mt-vat` (Malta), `:sk-vat` (Slovakia), `:lt-vat` (Lithuania), `:cy-vat` (Cyprus). Clean-room,
  each verified against a real published number.
- `:ro-vat` (Romania CUI), `:sg-nric` (Singapore NRIC/FIN, S/T/F/G series), `:hk-id` (Hong Kong
  HKID), `:kr-brn` (South Korea Business Registration Number). Clean-room, each verified against a
  real published number.
- Commerce / vehicle / healthcare: `:ean13` (EAN-13/GTIN-13 barcode), `:upc` (UPC-A), `:vin` (ISO
  3779 vehicle ID), `:nhs` (UK NHS number), `:npi` (US National Provider Identifier). Barcode checks
  are engine-backed (Commons Validator EAN-13); VIN/NHS/NPI are clean-room, each verified against a
  real published number.

## [0.5.0] - 2026-06-24

Coverage expansion: 40 -> 54 identifier types, spanning ~24 countries. Every checksum clean-room
from the public standard and verified against published example numbers.

### Added
- More VAT/GST: `:gr-vat` `:lu-vat` `:si-vat` `:ee-vat` `:hu-vat` `:in-gstin` (India GST, base-36
  check char).
- More tax & national IDs: `:pt-nif` `:cz-ico` `:jp-cn` `:au-tfn` `:hr-oib` (Croatia OIB),
  `:it-cf` (Italy codice fiscale, mod-26 check letter), `:ch-uid` `:ch-ahv` (Switzerland).

## [0.4.0] - 2026-06-23

Big coverage expansion: 13 -> 40 identifier types. Every new checksum is clean-room from the
public standard and verified against published example numbers (no third-party port; stays EPL).

### Added
- VAT numbers, country prefix optional on input: `:de-vat` `:fr-vat` `:it-vat` `:be-vat`
  `:pl-vat` `:gb-vat` `:at-vat` `:dk-vat` `:fi-vat` `:se-vat`.
- National IDs: `:gb-nino`, `:ca-sin`, `:au-abn`, `:in-pan`, `:in-aadhaar`, `:es-dni`, `:es-nie`,
  `:nl-bsn`, `:cn-ric`, `:se-pnr`, `:za-id`, `:no-org`, `:tr-tc`.
- Banking: `:mx-clabe` (Mexico CLABE bank account).

### Changed
- README: trimmed Usage to the core API and grouped the identifier types by category in a
  collapsible block, so the page stays scannable as the set grows.

## [0.3.0] - 2026-06-23

### Added
- Securities identifiers (engine-backed check digits): `:cusip` (US/Canada), `:sedol` (UK/Ireland) -
  rounding out the `:isin`/`:lei` set.
- US national numbers, clean-room from the public structural rules: `:us-ssn` (area/group/serial
  rules + the SSA's reserved advertising numbers, with `format`) and `:us-ein` (IRS campus prefix
  validation, with `format`).

## [0.2.0] - 2026-06-23

### Added
- Three new identifier types, implemented clean-room from their public standards (no
  third-party port; stays EPL):
  - `:lei` - Legal Entity Identifier (ISO 17442, ISO 7064 mod-97-10).
  - `:br-cpf` - Brazil individual taxpayer registry, with `format`.
  - `:br-cnpj` - Brazil company registry, with `format`.
- `compact`/`format` now also strip `/` (used by the CNPJ written form).

Country-specific identifiers are keyed by an ISO-3166 prefix (e.g. `:br-cpf`).

## [0.1.0] - 2026-06-23

Initial release.

### Added
- `stdnum.core` - a unified facade for validating, parsing, and formatting standard
  identifier numbers, dispatched on a type keyword:
  - `valid?`, `parse`, `format`, `compact`, `detect`, `card-network`, and the `types` set.
  - Identifier types: `:credit-card` (with network detection), `:iban`, `:bic`, `:isbn`,
    `:issn`, `:isin`, `:aba` (US bank routing), `:imei`, `:luhn`.
- Idiomatic facade over Apache Commons Validator 1.10.1 and iban4j 3.2.11 - no algorithm
  reimplementation. Bad input data never throws; only an unknown identifier type does.

[0.12.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.12.0
[0.11.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.11.0
[0.10.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.10.0
[0.9.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.9.0
[0.8.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.8.0
[0.7.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.7.0
[0.6.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.6.0
[0.5.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.5.0
[0.4.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.4.0
[0.3.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.3.0
[0.2.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.2.0
[0.1.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.1.0
