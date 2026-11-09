# Spaiter VPN for Android

The Android client of [spaiter-vpn](https://github.com/spaiter/spaiter-vpn). It is a
fork of [NekoBox+](https://github.com/starifly/NekoBoxForAndroid) by starifly, itself a
fork of [NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid) by
MatsuriDayo, and keeps their GPL-3.0 licence. The name, icon and application id are
ours; the upstream projects are not affiliated with this one.

[![API](https://img.shields.io/badge/API-23%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=23)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

## Supported protocols

* SOCKS (4/4a/5)
* HTTP(S)
* SSH
* Shadowsocks
* ShadowsocksR
* VMess
* Trojan
* VLESS (TCP, WebSocket, gRPC, HTTPUpgrade, XHTTP; TLS and Reality)
* AnyTLS/AnyReality
* Snell 1/2/3/4/5
* ShadowTLS
* TUIC
* Juicity
* Hysteria 1/2
* WireGuard
* AmneziaWG
* MASQUE
* Trojan-Go (trojan-go-plugin)
* NaïveProxy
* Mieru (mieru-plugin)
* ByeDPI
* MasterDnsVPN
* Tailscale

## Supported subscription formats

* Shadowsocks, Clash Meta and v2rayN style lists
* Remnawave (only with Happ / v2RayTun spoof)
* sing-box outbounds

Only outbounds, i.e. nodes, are imported. Routing rules in a subscription are ignored.

### XHTTP extra configuration

The XHTTP transport takes an "extra" JSON object, in the same shape Xray uses. A minimal
example with a separate download path over Reality:

```json
{
	"headers": { "User-Agent": "Mozilla/5.0" },
	"x_padding_bytes": "100-10000",
	"sc_max_each_post_bytes": 1000000,
	"sc_min_posts_interval_ms": 30,
	"xmux": {
		"max_concurrency": "16-32",
		"h_max_request_times": "600-900",
		"h_max_reusable_secs": "1800-3000"
	},
	"download": {
		"mode": "auto",
		"host": "example.com",
		"path": "/xhttp",
		"server": "cdn.example.com",
		"server_port": 443,
		"tls": {
			"enabled": true,
			"server_name": "example.com",
			"reality": { "enabled": true, "public_key": "…", "short_id": "…" },
			"utls": { "enabled": true, "fingerprint": "chrome" }
		}
	}
}
```

For plain TLS drop the `reality` block and set `"alpn": "h2"`. The padding, session, seq
and uplink fields (`x_padding_*`, `session_placement`/`session_key`, `seq_placement`/
`seq_key`, `uplink_data_placement`/`uplink_data_key`, `uplink_http_method`,
`uplink_chunk_size`) are accepted as well and default to off.

## Building

The Go core (`libcore`) is built from this repository plus seven sibling checkouts, all
forks kept under <https://github.com/spaiter> with the full history of their upstreams:

```text
workspace/
  spaiter-vpn-android/   this repository
  sing-box/              spaiter/android-sing-box      (COMMIT_SING_BOX in buildScript/lib/core/get_source_env.sh)
  sing-vmess/            spaiter/android-sing-vmess    (COMMIT_SING_VMESS)
  wireguard-go/          spaiter/android-wireguard-go  (branch nekoboxplus-patched)
  byedpi/                spaiter/android-byedpi        (branch nekoboxplus-jni)
  amneziawg-go/          spaiter/android-amneziawg-go  (branch awg-v3.1)
  utls/                  spaiter/android-utls          (branch wip-safety-20260726)
  MasterDnsVPN/          spaiter/android-masterdnsvpn  (branch nekoboxplus-patched)
```

`buildScript/lib/core/get_source.sh` clones `sing-box`, `sing-vmess`, `wireguard-go` and
`byedpi` when they are missing and checks out the pinned commits; clone the other three by
hand at the branches above (set `SIBLINGS_GIT` to clone everything from a mirror). Keep the
pins in `get_source_env.sh` in step with the siblings, or the build breaks.

Install the Android SDK/NDK, Docker (or a compatible Podman) and a JDK 17. Then download the
runtime assets and build the core inside the container:

```bash
cd spaiter-vpn-android
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

bash buildScript/lib/assets.sh
bash buildScript/lib/core.docker.sh
```

The containerized build is required because the core is compiled with a patched Go runtime
(it fixes `PersistentKeepalive` for WireGuard/AmneziaWG on kernels 4.17 and older). Rebuild
the image after changing the Go version, the Dockerfile or the runtime patch:

```bash
bash buildScript/lib/core.docker.sh --rebuild-image
```

Useful knobs for `core.docker.sh`:

- `SING_BOX_SRC`, `SING_VMESS_SRC`, `AMNEZIAWG_GO_SRC`, `WIREGUARD_GO_SRC`, `MASTERDNSVPN_SRC`,
  `BYEDPI_SRC`, `UTLS_SRC`: override sibling locations.
- `LIBCORE_TMP_HOST`: directory for the build's temporary files (put it on disk when `/tmp` is
  a small tmpfs).
- `AAR_OUT_DIR`, `GO_CACHE_DIR`, `GO_MOD_CACHE_DIR`: output and Go cache directories.
- `GO_VERSION`, `BOOTSTRAP_GO_VERSION`, `GO_PATCH_DIR`, `DOCKERFILE`, `PATCHED_GO_ANDROID_IMAGE`:
  customise the patched Go image. Runtime patches are applied from `GO_PATCH_DIR` in lexical order.
- `DOCKER_RUN_EXTRA_ARGS`: extra Docker/Podman flags, e.g. `-e GOMOBILE_TARGET=android/arm64`
  to build a single ABI, or `--network host`.

Then build the app (the shipped flavour is `plus`):

```bash
./gradlew :app:packagePlusReleaseUniversalApk
```

A debug build for a device or emulator: `./gradlew assembleOssDebug`. Unit tests:
`./gradlew :app:testOssDebugUnitTest`. Release signing takes its values from
`local.properties` or the `KEYSTORE_PASS`, `ALIAS_NAME` and `ALIAS_PASS` environment
variables; the repository ships no keystore.

## Licence and third-party code

GPL-3.0-or-later, see [LICENSE](LICENSE). Everything the build links or bundles, with its
licence, is listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## Credits

Core:

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [starifly/sing-box](https://github.com/starifly/sing-box)

Android GUI:

- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)
- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)

Web Dashboard:

- [metacubexd](https://github.com/MetaCubeX/metacubexd)
