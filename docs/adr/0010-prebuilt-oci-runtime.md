# ADR 0010 · Keep image packaging outside the compiler

## Status

Accepted

## Decision

Consume a prebuilt OCI image reference in the first slice. Declare
container port and optional HTTP health path as service-neutral facts.
Build the fixture's executable JAR separately and package it with a
Dockerfile using a pinned JRE image index. Other image builders can
produce the same input contract.

## Evidence

The smoke test runs analysis before the image build, resolves the exact
image and health metadata, then builds and runs the fixture and checks
its HTTP health response. Input tests reject embedded image build modes.

## Consequences

Compose and K3s use the same image reference semantics. Registry
availability and platform compatibility belong to the target workflow.
The compiler can run without access to a Docker engine.
