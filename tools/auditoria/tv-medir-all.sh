#!/bin/bash
# roda o roteiro da TV alternando as duas versões no MESMO emulador (base, novo, base, novo, ...) para a deriva do emulador não favorecer nenhuma
cd "$(dirname "$0")/../.."
for i in 1 2 3; do
  bash tools/auditoria/tv-medir.sh /tmp/base.apk base "$i"
  bash tools/auditoria/tv-medir.sh /tmp/head.apk head "$i"
done
bash tools/auditoria/tv-medir-final.sh /tmp/base.apk base
bash tools/auditoria/tv-medir-final.sh /tmp/head.apk head
