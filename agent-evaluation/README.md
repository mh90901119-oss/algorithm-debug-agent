# Agent evaluation fixtures

This module will host the deterministic evaluator in P8. Until that runner exists, versioned Golden
fixtures document capability expectations and are syntax-checked during the owning phase audit. They
must not be reported as model-quality scores or as an OpenCode end-to-end result.

`p3-jdwp-decision-cases-v1.json` covers the Algorithm Debug Skill's JDWP decision boundary: reuse
sufficient history, request only minimal missing evidence, keep target failures analyzable, reject
unusable collections, and avoid turning a hypothesis into a fact.
