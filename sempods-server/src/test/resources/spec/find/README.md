# Vendored find examples

`responses.yaml` is a copy of sempods-spec
[`examples/find/responses.yaml`](https://github.com/sempods/sempods-spec/blob/2e543168f7ac0d6d850ec2f7d66224c927cdf62d/examples/find/responses.yaml)
at `2e543168`. Nothing in this repository edits it. `FindSpecExamplesHttpTest` serves its cases
through `_system/find` and compares the answers with it.

To upgrade, copy the file again and run that test:

```bash
curl -fsSL https://raw.githubusercontent.com/sempods/sempods-spec/main/examples/find/responses.yaml \
  -o sempods-server/src/test/resources/spec/find/responses.yaml
```
