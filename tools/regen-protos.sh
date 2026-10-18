#!/usr/bin/env bash
# Rebuilds the descriptor set mobius reads at runtime from the protos in proto/.
#
#   tools/regen-protos.sh                        # write build/libs/mobius.desc
#   tools/regen-protos.sh --out /srv/mobius      # write mobius.desc there
#   tools/regen-protos.sh --proto /path/to/proto # read protos from there
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
protos="$root/proto"
out="$root/build/libs"

while [ $# -gt 0 ]; do
  case "$1" in
    --proto) protos="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

command -v protoc >/dev/null || { echo "protoc is not installed" >&2; exit 1; }

mkdir -p "$out"
protoc --descriptor_set_out="$out/mobius.desc" --include_imports \
       --proto_path="$protos" "$protos"/*.proto
echo "wrote $out/mobius.desc ($(wc -c < "$out/mobius.desc") bytes)"
