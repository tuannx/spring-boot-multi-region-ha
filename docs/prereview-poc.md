# PreReview JVM POC

This proof of concept validates a config-driven, plugin-shaped asynchronous evaluation runtime on the real Spring Boot HA repository.

## Evaluation graph

    diff
     |- build -- test
     `- structure

Build and structure run concurrently. Tests run only when build passes. An independent node still completes when another branch fails.

## Intentionally closed early

- config -> graph -> execution -> result -> report flow
- explicit dependency semantics
- deterministic gates
- trigger-independent project working directory

## Intentionally open later

- public plugin SDK
- normalized Finding / Evidence schema
- SARIF and JUnit parsers
- baseline and new-finding policy
- change-impact analysis
- LLM investigator

No LLM, database, server, custom analyzer, or distributed executor is required for this POC.
