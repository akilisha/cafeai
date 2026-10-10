Test vectors from the AWS Signature Version 4 test suite, as published in
awslabs/aws-c-auth (tests/aws-signing-test-suite/v4), Apache License 2.0:
https://github.com/awslabs/aws-c-auth/tree/main/tests/aws-signing-test-suite/v4

Each directory holds the request, its context (credentials, region, service, time),
and the canonical request, string to sign and signature AWS expects.
