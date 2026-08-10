# Add an Identifier Type

The cited corpus in `resources/stdnum/vectors.edn` drives every shipped
identifier type.

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
2. Add a `resources/stdnum/vectors.edn` entry with `:valid`, `:invalid`, and
   mandatory `:source`.
3. Register the validator in `stdnum.core`.
4. Run the suite.

For EU VAT values that are companies with a confirmed live registration, add
`:vies true`. The integration test checks those again against the live VIES
service.

## Conditions to publish

Do not publish a validator that rejects valid real-world numbers.

Do not publish ambiguous formats or formats that you cannot verify. Wait until
a confirmed real vector from an authoritative source exists. The corpus sets
what the validator can claim.

`stdnum-clj` follows the same basic idea as python-stdnum: standard numbers are
small APIs, but their correctness depends on published examples and testable
rules.
