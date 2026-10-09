#!/usr/bin/env bash
# Prints the BCrypt hash of a password you type (hidden, twice) - for the manual password reset of a COACH (see CLAUDE.md).
# The password is read from the terminal and passed on STANDARD INPUT only: never in arguments, never in the shell history.
# Needs the JDK 21 and the backend dependencies already downloaded (run `mvn -q compile` once in backend/).
#   scripts/bcrypt-hash.sh                 -> prints the hash
#   scripts/bcrypt-hash.sh check '<hash>'  -> asks for a password and says MATCH / NO MATCH
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
M2="${HOME}/.m2/repository"
CRYPTO="$(find "$M2/org/springframework/security/spring-security-crypto" -name 'spring-security-crypto-*.jar' ! -name '*sources*' 2>/dev/null | sort | tail -1)"
JCL="$(find "$M2/org/springframework/spring-jcl" -name 'spring-jcl-*.jar' ! -name '*sources*' 2>/dev/null | sort | tail -1)"
CORE="$(find "$M2/org/springframework/spring-core" -name 'spring-core-*.jar' ! -name '*sources*' 2>/dev/null | sort | tail -1)"
if [[ -z "$CRYPTO" || -z "$JCL" || -z "$CORE" ]]; then
  echo "spring-security-crypto / spring-core not found in $M2: run 'mvn -q compile' in backend/ first." >&2
  exit 1
fi
read -rsp "Password: " PASSWORD; echo >&2
if [[ "${1:-}" != "check" ]]; then
  read -rsp "Repeat it: " AGAIN; echo >&2
  [[ "$PASSWORD" == "$AGAIN" ]] || { echo "They do not match." >&2; exit 1; }
fi
printf '%s\n' "$PASSWORD" | "$JAVA_BIN" -cp "$CRYPTO:$CORE:$JCL" "$HERE/BcryptHash.java" "$@"
