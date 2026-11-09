# Third-party notices

Spaiter VPN for Android is GPL-3.0-or-later. This file lists what the build links or bundles,
with the licence each component is distributed under. It was generated from the module graph of
`libcore` as built for android/arm64 with the production tags, from the Gradle runtime classpath
of the `plus` flavour, from the Rust adblock bridge lockfile and from the assets directory.

## Lineage

- [NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid), MatsuriDayo, GPL-3.0: the application this descends from.
- [NekoBox+](https://github.com/starifly/NekoBoxForAndroid), starifly, GPL-3.0: the intermediate fork whose protocol additions and UI this carries.
- [sing-box](https://github.com/SagerNet/sing-box), nekohasekai, GPL-3.0 with a naming clause: the core, through a fork kept in this repository set. This application is not sing-box and does not claim association with it.
- `libcore/corelib` holds GPL-3.0 helper code of our own; the `libneko` helpers it stands in for are not used, because libneko carries no licence.

## Go modules linked into libcore

| Module | Version | Source | Licence |
|---|---|---|---|
| `filippo.io/edwards25519` | `v1.1.0` |  | BSD-3-Clause |
| `github.com/ajg/form` | `v1.5.1` |  | BSD-3-Clause |
| `github.com/amnezia-vpn/amneziawg-go/v3` | `v3.1.20260814` | fork in this repository set (amneziawg-go) | MIT |
| `github.com/andybalholm/brotli` | `v1.2.0` |  | MIT |
| `github.com/andybalholm/cascadia` | `v1.3.4` |  | BSD-2-Clause |
| `github.com/antchfx/htmlquery` | `v1.3.6` |  | MIT |
| `github.com/antchfx/xpath` | `v1.3.6` |  | MIT |
| `github.com/anytls/sing-anytls` | `v0.0.13` |  | GPL-3.0 |
| `github.com/bdandy/go-errors` | `v1.2.2` |  | MIT |
| `github.com/bdandy/go-socks4` | `v1.2.3` |  | MIT |
| `github.com/bogdanfinn/fhttp` | `v0.6.8` |  | BSD-3-Clause (Go net/http fork; notice kept in file headers, no LICENSE file upstream) |
| `github.com/bogdanfinn/quic-go-utls` | `v1.0.9-utls` |  | MIT |
| `github.com/bogdanfinn/tls-client` | `v1.14.0` |  | BSD-3-Clause |
| `github.com/bogdanfinn/utls` | `v1.7.7-barnius` |  | BSD-3-Clause |
| `github.com/bogdanfinn/websocket` | `v1.5.5-barnius` |  | BSD-2-Clause |
| `github.com/BurntSushi/toml` | `v1.4.0` |  | MIT |
| `github.com/cbeuw/connutil` | `v1.0.1` |  | Apache-2.0 |
| `github.com/cespare/xxhash/v2` | `v2.3.0` |  | MIT |
| `github.com/cretz/bine` | `v0.2.0` |  | MIT |
| `github.com/database64128/netx-go` | `v0.1.1` |  | BSD-3-Clause |
| `github.com/database64128/tfo-go/v2` | `v2.3.2` |  | MIT |
| `github.com/dunglas/httpsfv` | `v1.1.0` |  | BSD-3-Clause |
| `github.com/dyhkwong/sing-juicity` | `v0.1.2` |  | GPL-3.0 |
| `github.com/enfein/mieru/v3` | `v3.34.0` |  | GPL-3.0 |
| `github.com/florianl/go-nfqueue/v2` | `v2.0.2` |  | MIT |
| `github.com/fsnotify/fsnotify` | `v1.9.0` |  | BSD-3-Clause |
| `github.com/fxamacker/cbor/v2` | `v2.7.0` |  | MIT |
| `github.com/gaissmai/bart` | `v0.18.0` |  | MIT |
| `github.com/gobwas/httphead` | `v0.1.0` |  | MIT |
| `github.com/gobwas/pool` | `v0.2.1` |  | MIT |
| `github.com/goccy/go-json` | `v0.10.6` |  | MIT |
| `github.com/go-chi/chi/v5` | `v5.2.5` |  | MIT |
| `github.com/go-chi/render` | `v1.0.3` |  | MIT |
| `github.com/godbus/dbus/v5` | `v5.2.2` |  | BSD-2-Clause |
| `github.com/gofrs/uuid/v5` | `v5.4.0` |  | MIT |
| `github.com/go-json-experiment/json` | `v0.0.0-20250813024750-ebf49471dced` |  | BSD-3-Clause |
| `github.com/golang/groupcache` | `v0.0.0-20210331224755-41bb18bfe9da` |  | Apache-2.0 |
| `github.com/golang/protobuf` | `v1.5.4` |  | BSD-3-Clause |
| `github.com/google/btree` | `v1.1.3` |  | Apache-2.0 |
| `github.com/google/shlex` | `v0.0.0-20191202100458-e7afc7fbc510` |  | Apache-2.0 |
| `github.com/google/uuid` | `v1.6.0` |  | BSD-3-Clause |
| `github.com/hashicorp/yamux` | `v0.1.2` |  | MPL-2.0 |
| `github.com/hdevalence/ed25519consensus` | `v0.2.0` |  | BSD-3-Clause |
| `github.com/klauspost/compress` | `v1.18.5` |  | Apache-2.0 |
| `github.com/klauspost/cpuid/v2` | `v2.3.0` |  | MIT |
| `github.com/logrusorgru/aurora` | `v2.0.3+incompatible` |  | Unlicense |
| `github.com/mdlayher/netlink` | `v1.9.0` |  | MIT |
| `github.com/mdlayher/socket` | `v0.5.1` |  | MIT |
| `github.com/metacubex/chacha` | `v0.1.5` |  | MIT |
| `github.com/metacubex/mihomo` | `v1.19.13` |  | GPL-3.0 |
| `github.com/metacubex/randv2` | `v0.2.0` |  | GPL-3.0 |
| `github.com/metacubex/sing` | `v0.5.5` |  | GPL-3.0 |
| `github.com/metacubex/utls` | `v1.8.4` | fork in this repository set (utls) | BSD-3-Clause |
| `github.com/miekg/dns` | `v1.1.72` |  | BSD-3-Clause |
| `github.com/oschwald/maxminddb-golang` | `v1.13.1` |  | ISC |
| `github.com/pierrec/lz4/v4` | `v4.1.26` |  | BSD-3-Clause |
| `github.com/pion/dtls/v3` | `v3.1.2` |  | MIT |
| `github.com/pion/logging` | `v0.2.4` |  | MIT |
| `github.com/pion/randutil` | `v0.1.0` |  | MIT |
| `github.com/pion/stun/v3` | `v3.1.1` |  | MIT |
| `github.com/pion/transport/v4` | `v4.0.1` |  | MIT |
| `github.com/pion/turn/v5` | `v5.0.2` |  | MIT |
| `github.com/pires/go-proxyproto` | `v0.8.1` |  | Apache-2.0 |
| `github.com/quic-go/masque-go` | `v0.4.0` |  | MIT |
| `github.com/quic-go/qpack` | `v0.6.0` |  | MIT |
| `github.com/quic-go/quic-go` | `v0.60.0` |  | MIT |
| `github.com/refraction-networking/utls` | `v1.8.2` |  | BSD-3-Clause |
| `github.com/sagernet/bbolt` | `v0.0.0-20231014093535-ea5cb2fe9f0a` |  | MIT |
| `github.com/sagernet/cors` | `v1.2.1` |  | MIT |
| `github.com/sagernet/fswatch` | `v0.1.2` |  | GPL-3.0 |
| `github.com/sagernet/gomobile` | `v0.1.12` |  | BSD-3-Clause |
| `github.com/sagernet/gvisor` | `v0.0.0-20250811.0-sing-box-mod.1` | github.com/spaiter/gvisor v0.0.0-20261106193230-3dd6df2db1b2 | Apache-2.0 |
| `github.com/sagernet/netlink` | `v0.0.0-20240612041022-b9a21c07ac6a` |  | Apache-2.0 |
| `github.com/sagernet/nftables` | `v0.3.0-mod.2` |  | Apache-2.0 |
| `github.com/sagernet/quic-go` | `v0.59.0-sing-box-mod.5` |  | MIT |
| `github.com/sagernet/sing-box` | `v1.0.0` | fork in this repository set (sing-box) | GPL-3.0 |
| `github.com/sagernet/sing-mux` | `v0.3.5` |  | GPL-3.0 |
| `github.com/sagernet/sing-quic` | `v0.6.3` |  | GPL-3.0 |
| `github.com/sagernet/sing-shadowsocks2` | `v0.2.1` |  | GPL-3.0 |
| `github.com/sagernet/sing-shadowsocks` | `v0.2.8` |  | GPL-3.0 |
| `github.com/sagernet/sing-shadowtls` | `v0.2.1-0.20250503051639-fcd445d33c11` |  | GPL-3.0 |
| `github.com/sagernet/sing-snell` | `v0.0.0-20260710094516-a4e97ee24beb` | github.com/reF1nd/sing-snell v0.0.0-20260713132549-9711346e2b35 | GPL-3.0 |
| `github.com/sagernet/sing-tun` | `v0.8.11` |  | GPL-3.0 |
| `github.com/sagernet/sing` | `v0.8.11` |  | GPL-3.0 |
| `github.com/sagernet/sing-vmess` | `v0.2.8-0.20250909125414-3aed155119a1` | fork in this repository set (sing-vmess) | GPL-3.0 |
| `github.com/sagernet/smux` | `v1.5.50-sing-box-mod.1` |  | MIT |
| `github.com/sagernet/tailscale` | `v1.92.4-sing-box-1.13-mod.7` |  | BSD-3-Clause |
| `github.com/sagernet/wireguard-go` | `v0.0.2-beta.1.0.20260224074747-506b7631853c` | fork in this repository set (wireguard-go) | MIT |
| `github.com/sagernet/ws` | `v0.0.0-20231204124109-acfe8907c854` |  | MIT |
| `github.com/sirupsen/logrus` | `v1.9.3` |  | MIT |
| `github.com/tailscale/goupnp` | `v1.0.1-0.20210804011211-c64d0f06ea05` |  | BSD-2-Clause |
| `github.com/tailscale/peercred` | `v0.0.0-20250107143737-35a0c7bd7edc` |  | BSD-3-Clause |
| `github.com/tam7t/hpkp` | `v0.0.0-20160821193359-2b70b4024ed5` |  | MIT |
| `github.com/ulikunitz/xz` | `v0.5.15` |  | BSD-2-Clause |
| `github.com/vishvananda/netns` | `v0.0.5` |  | Apache-2.0 |
| `github.com/wasilibs/go-re2` | `v1.12.0` |  | MIT |
| `github.com/wlynxg/anet` | `v0.0.5` |  | BSD-3-Clause |
| `github.com/x448/float16` | `v0.8.4` |  | MIT |
| `github.com/yosida95/uritemplate/v3` | `v3.0.2` |  | BSD-3-Clause |
| `gitlab.com/go-extension/aes-ccm` | `v0.0.0-20230221065045-e58665ef23c7` |  | MIT |
| `go4.org/mem` | `v0.0.0-20240501181205-ae6ca9944745` |  | Apache-2.0 |
| `go4.org/netipx` | `v0.0.0-20231129151722-fdeea329fbba` |  | BSD-3-Clause |
| `golang.org/x/crypto` | `v0.54.0` |  | BSD-3-Clause |
| `golang.org/x/exp` | `v0.0.0-20260603202125-055de637280b` |  | BSD-3-Clause |
| `golang.org/x/mod` | `v0.37.0` |  | BSD-3-Clause |
| `golang.org/x/net` | `v0.57.0` | github.com/LLC-INCY/x-net v0.56.1-0.20260622225635-7f8fbb7ea469 | BSD-3-Clause |
| `golang.org/x/oauth2` | `v0.36.0` |  | BSD-3-Clause |
| `golang.org/x/sync` | `v0.22.0` |  | BSD-3-Clause |
| `golang.org/x/sys` | `v0.47.0` |  | BSD-3-Clause |
| `golang.org/x/term` | `v0.45.0` |  | BSD-3-Clause |
| `golang.org/x/text` | `v0.40.0` |  | BSD-3-Clause |
| `golang.org/x/time` | `v0.11.0` |  | BSD-3-Clause |
| `golang.zx2c4.com/wireguard/wgctrl` | `v0.0.0-20241231184526-a9ab2273dd10` |  | MIT |
| `google.golang.org/genproto/googleapis/rpc` | `v0.0.0-20260706201446-f0a921348800` |  | Apache-2.0 |
| `google.golang.org/genproto` | `v0.0.0-20240123012728-ef4313101c80` |  | Apache-2.0 |
| `google.golang.org/grpc` | `v1.82.0` |  | Apache-2.0 |
| `google.golang.org/protobuf` | `v1.36.11` |  | BSD-3-Clause |
| `gvisor.dev/gvisor` | `v0.0.0-20231202080848-1f7806d17489` |  | Apache-2.0 |
| `lukechampine.com/blake3` | `v1.3.0` |  | MIT |
| `masterdnsvpn-go` | `v0.0.0-00010101000000-000000000000` | fork in this repository set (MasterDnsVPN) | MIT |

Summary: MIT 47, BSD-3-Clause 33, GPL-3.0 17, Apache-2.0 15, BSD-2-Clause 5, MPL-2.0 1, Unlicense 1, ISC 1.

## Gradle runtime dependencies (plus flavour)

AndroidX, Kotlin, kotlinx, Google Material, Guava, error-prone and checkerframework artifacts are Apache-2.0 (checkerframework qual: MIT). Other artifacts:

| Artifact | Licence |
|---|---|
| com.blacksquircle.ui:editorkit, language-base, language-json | Apache-2.0 |
| com.esotericsoftware:kryo, minlog, reflectasm | BSD-3-Clause |
| com.github.daniel-stoneuk:material-about-library | Apache-2.0 |
| com.github.MatrixDev.Roomigrant:RoomigrantLib | Apache-2.0 |
| com.google.code.gson:gson | Apache-2.0 |
| com.google.code.findbugs:jsr305, com.google.auto.value, com.google.j2objc | Apache-2.0 / BSD-3-Clause (jsr305) |
| com.google.zxing:core | Apache-2.0 |
| com.jakewharton:process-phoenix | Apache-2.0 |
| com.simplecityapps:recyclerview-fastscroll | Apache-2.0 |
| com.squareup.okhttp3:okhttp, com.squareup.okio | Apache-2.0 |
| org.ini4j:ini4j | Apache-2.0 |
| org.jspecify:jspecify | Apache-2.0 |
| org.objenesis:objenesis | Apache-2.0 |
| org.yaml:snakeyaml | Apache-2.0 |

## Rust adblock bridge

`sing-box/common/adblock/bridge` (ours, GPL-3.0) links [adblock-rust](https://github.com/brave/adblock-rust) (MPL-2.0) and its MIT/Apache-2.0 crate graph (see its `Cargo.lock`). Filter resources are generated from uBlock Origin (GPL-3.0) at build time.

## Bundled assets

- `geoip.db`: built by [SagerNet/sing-geoip](https://github.com/SagerNet/sing-geoip) (GPL-3.0 tooling) from a Country.mmdb derived from MaxMind GeoLite2. This product includes GeoLite2 data created by MaxMind, available from <https://www.maxmind.com>.
- `geosite.db`: built by [SagerNet/sing-geosite](https://github.com/SagerNet/sing-geosite) from [v2fly/domain-list-community](https://github.com/v2fly/domain-list-community) (MIT).
- Rule-set list `srslist.h` from [throneproj/routeprofiles](https://github.com/throneproj/routeprofiles): a list of URLs, no code.
- `metacubexd.tgz`: [MetaCubeX/metacubexd](https://github.com/MetaCubeX/metacubexd) dashboard, MIT.
- Launcher icons and the application name are ours (GPL-3.0 like the rest).

## Not shipped

- Cronet (`sagernet/cronet-go`, Chromium BSD) is not linked: the `with_naive_outbound`, `with_trusttunnel_cronet` and `with_adblock_cronet` tags are off in release builds.
- The x86/x86_64 and armeabi-v7a native cores are not built; release APKs carry arm64-v8a only.
