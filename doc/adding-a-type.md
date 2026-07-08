# Adding an Identifier Type

Every shipped identifier type is driven by the cited corpus in
`test/stdnum/vectors.edn`.

Each corpus entry has this shape:

```clojure
:iban {:valid ["GB82WEST12345698765432"]
       :invalid ["GB82WEST12345698765433"]
       :source "ISO 13616 worked example"}
```

`:source` is mandatory. It names the standard, registry, government page,
issuer publication, or live service that makes the vector re-checkable.

## Process

1. Find a real published number from an authoritative source.
2. Add a `test/stdnum/vectors.edn` entry with `:valid`, `:invalid`, and
   mandatory `:source`.
3. Register the validator in `stdnum.core`.
4. Run the suite.

For EU VAT values that are confirmed as live-registered companies, add
`:vies true`; the integration test re-checks those against the live VIES
service.

## Bar To Ship

Do not publish a validator that rejects valid real-world numbers.

Ambiguous or unverifiable formats stay out until there is a confirmed real vector
from an authoritative source. The corpus comes first because it defines what the
validator is allowed to claim.

`stdnum-clj` follows the same basic idea as python-stdnum: standard numbers are
small APIs, but their correctness depends on published examples and testable
rules.
