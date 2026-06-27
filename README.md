# Lingo (Android)

YouTube 動画を **英語＋日本語の同時字幕**で再生する英語学習アプリの Android 版。

Web 版 (https://yuichi916.github.io/lingo.html) と同じ UI・機能（カラオケ字幕／A‑B リピート／速度変更／タップ辞書／単語帳）を、**ネイティブ WebView アプリ**として動かす。

## なぜネイティブアプリなのか

Web 版の根本問題は「YouTube の字幕本文 (timedtext) が取得できない」こと：

- Cloudflare / データセンター IP は YouTube に **bot 判定 (LOGIN_REQUIRED)** されブロックされる
- ブラウザの **CORS** が timedtext への直接アクセスを禁じる
- 現行 YouTube は timedtext に **poToken（proof‑of‑origin）** を要求する

ネイティブアプリはこれを構造的に回避できる：

1. **住宅/モバイル IP** — アプリは端末自身の回線で通信するので bot 判定されない
2. **CORS 無し** — ネイティブ HTTP (`LingoNative.httpGet`) はオリジン制約を受けない
3. **poToken 突破** — WebView は再生中の**公式プレイヤーが出す通信を覗ける** (`shouldInterceptRequest`)。
   プレイヤー自身が組み立てた **有効な poToken 付き `/api/timedtext` URL を横取り**し、
   ネイティブ HTTP で全文取得する（poToken の自前生成不要）

→ サーバにも純 Web ページにも原理的に不可能な経路を、ネイティブアプリだけが実現する。

## 字幕取得の二段構え

`app/src/main/assets/index.html` の `fetchCuesViaNative()`：

1. **direct**: `httpGet(watch ページ)` → `ytInitialPlayerResponse` → `captionTracks` → `httpGet(timedtext)`
   （住宅 IP で空が返らなければ最速）
2. **capture**: 空/ゲート時 → 公式プレイヤーが要求した pot 署名済み timedtext URL を横取り → `lang=en` に書換えて全文取得

翻訳・検索は既存の Cloudflare Worker (`lingo-transcript`) をそのまま利用（CORS は `*` 許可済み）。

## ビルド

ローカルに Android SDK は不要。**GitHub Actions がクラウドで APK をビルド**する：

1. このディレクトリを GitHub リポジトリ (`yuichi916/lingo-android`) に push
2. `.github/workflows/build-apk.yml` が走り、`out/lingo.apk` を生成
3. Actions の artifact、または `latest` リリースから APK をダウンロード
4. Android 端末で「提供元不明のアプリ」を許可してインストール（sideload）

ローカルでビルドする場合（要 JDK 17 + Android SDK + Gradle 8.7+）：

```
gradle :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

## 配布について

YouTube の ToS 上、Play Store 公開はリスクがあるため **APK 直接配布 (sideload)** を前提とする。
個人・コミュニティ利用向け。

## 構成

```
app/src/main/java/io/lingo/app/MainActivity.kt   ネイティブ層（WebView＋httpGet＋timedtext横取り）
app/src/main/assets/index.html                   lingo 本体（Web 版＋ネイティブ字幕経路を注入）
.github/workflows/build-apk.yml                  クラウド APK ビルド
```
