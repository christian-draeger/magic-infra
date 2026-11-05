rootProject.name = "magic-infra"

include(":spring-evidence", ":evidence-check")
project(":spring-evidence").projectDir = file("adapters/spring")
project(":evidence-check").projectDir = file("compiler/evidence")
