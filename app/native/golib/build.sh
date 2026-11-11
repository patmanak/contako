#!/usr/bin/env bash
# Linux reference build; source inputs and packaged output remain checksum-guarded.
set -euo pipefail
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
go_home="${GOROOT:-}"
sdk="${ANDROID_HOME:-}"
ndk="${ANDROID_NDK_HOME:-}"
java_home="${JAVA_HOME:-}"
work="$source_dir/build"
repository="$source_dir/../build/maven"
while (($#)); do
  case "$1" in
    --go-home|--android-sdk|--android-ndk|--java-home|--work|--repository)
      (($# >= 2)) || { echo MISSING_ARGUMENT >&2; exit 1; }
      case "$1" in
        --go-home) go_home="$2" ;; --android-sdk) sdk="$2" ;;
        --android-ndk) ndk="$2" ;; --java-home) java_home="$2" ;;
        --work) work="$2" ;; --repository) repository="$2" ;;
      esac
      shift 2 ;;
    *) echo UNKNOWN_ARGUMENT >&2; exit 1 ;;
  esac
done
[[ "$(uname -s)" == Linux ]] || { echo LINUX_REFERENCE_REQUIRED >&2; exit 1; }
[[ -x "$go_home/bin/go" ]] || { echo GO_HOME_INVALID >&2; exit 1; }
[[ "$("$go_home/bin/go" env GOVERSION)" == go1.27.1 ]] || { echo GO_1_27_1_REQUIRED >&2; exit 1; }
[[ -x "$java_home/bin/javac" ]] || { echo JAVA_HOME_INVALID >&2; exit 1; }
"$java_home/bin/javac" -version 2>&1 | grep -Eq '^javac 21([.]|$)' || { echo JAVA_21_REQUIRED >&2; exit 1; }
grep -Eq 'Pkg.Revision[[:space:]]*=[[:space:]]*27\.2\.12479018([[:space:]]|$)' "$ndk/source.properties" || { echo NDK_R27C_REQUIRED >&2; exit 1; }
[[ -d "$sdk/platforms" ]] || { echo ANDROID_SDK_INVALID >&2; exit 1; }
go_home="$(realpath "$go_home")"
java_home="$(realpath "$java_home")"
work="$(realpath -m "$work")"
repository="$(realpath -m "$repository")"
mkdir -p "$work/bin"
export GOROOT="$go_home" JAVA_HOME="$java_home" ANDROID_HOME="$sdk" ANDROID_NDK_HOME="$ndk"
export GOTOOLCHAIN=local GOMAXPROCS=2
export GOPATH="$work/gopath" GOMODCACHE="$work/modcache" GOCACHE="$work/gocache"
export GOPROXY=https://proxy.golang.org GOSUMDB=sum.golang.org
export PATH="$go_home/bin:$java_home/bin:$work/bin:$PATH"
cd "$source_dir"
sha256sum go.mod go.sum dependencies.go > "$work/source-lock.sha256"
go mod download > "$work/download.log" 2>&1
go build -mod=readonly -trimpath -o "$work/bin/gomobile" golang.org/x/mobile/cmd/gomobile > "$work/gomobile.log" 2>&1
go build -mod=readonly -trimpath -o "$work/bin/gobind" golang.org/x/mobile/cmd/gobind > "$work/gobind.log" 2>&1
gomobile init > "$work/init.log" 2>&1
gomobile bind -tags mobile -target android -androidapi 31 -javapkg com.proton.gopenpgp -trimpath \
  '-ldflags=-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384' \
  -o "$work/raw.aar" \
  github.com/ProtonMail/gopenpgp/v2/crypto github.com/ProtonMail/gopenpgp/v2/armor \
  github.com/ProtonMail/gopenpgp/v2/constants github.com/ProtonMail/gopenpgp/v2/models \
  github.com/ProtonMail/gopenpgp/v2/subtle github.com/ProtonMail/gopenpgp/v2/helper \
  github.com/ProtonMail/go-srp > "$work/bind.log" 2>&1
go list -m -json all > "$work/modules.json"
sha256sum -c "$work/source-lock.sha256"
python3 - "$work/official.aar" <<'PY'
import hashlib, pathlib, sys, urllib.request
path = pathlib.Path(sys.argv[1])
if not path.exists():
    url = 'https://repo.maven.apache.org/maven2/me/proton/crypto/android-golib/2.10.0-2/android-golib-2.10.0-2.aar'
    with urllib.request.urlopen(url, timeout=120) as response:
        path.write_bytes(response.read())
if hashlib.sha256(path.read_bytes()).hexdigest() != 'f64705f4ae14dde5c619d03a3a7fc2e8d747fb2803c4b5da01b0c38dac7884c9':
    sys.exit('OFFICIAL_REFERENCE_HASH_MISMATCH')
PY
python3 package-aar.py --go "$go_home/bin/go" --java-home "$java_home" \
  --work "$work" --repository "$repository" > "$work/package.log" 2>&1
cat "$work/package.log"
