#!/bin/bash
cd /root/jm3
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}
TASK=${1:-:app:compileDebugKotlin}
timeout 1800 /root/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle \
  "$TASK" \
  -Pandroid.aapt2FromMavenOverride=/opt/aapt2w/aapt2 \
  --console=plain 2>&1 | grep -E "^e:|error:|BUILD|FAILURE|Unresolved|Caused by|AAPT" | head -120
