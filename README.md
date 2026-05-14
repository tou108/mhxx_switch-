# MHXX スナイプアプリ (sys-botbase版)

MHXXのお守りスナイプ用Androidアプリです。
Bluetooth HID方式から **sys-botbase TCP/IP方式** に変更されています。

## 必要なもの

- Android スマートフォン (Android 8.0以上)
- Nintendo Switch (CFW + sys-botbase インストール済み)
- 同一WiFiネットワーク

## sys-botbase のインストール

1. [sys-botbase](https://github.com/olliz0r/sys-botbase) の最新リリースをダウンロード
2. Switchの SDカードに展開
3. Switchを再起動 → Homeボタンが光れば起動成功
4. Switch の IPアドレスを確認 (本体設定 → インターネット)

## APKのビルド方法 (GitHub Actions)

1. このプロジェクトを自分の GitHub にリポジトリとして作成
2. ファイルをアップロード (または git push)
3. リポジトリの **Actions** タブを開く
4. **「Build APK」** ワークフローを選択
5. **「Run workflow」** ボタンをクリック
6. ビルド完了後、**Artifacts** から `MHXXSnipeApp-debug.apk` をダウンロード

> mainブランチへのpushでも自動ビルドされます。

## アプリの使い方

1. SwitchとスマホをWiFiで同じネットワークに接続
2. アプリを起動
3. **接続** タブでSwitchのIPアドレスとポート(6000)を入力
4. **接続** ボタンをタップ
5. **マクロ** タブでプリセットマクロを実行

## 変更点 (v2.0)

- Bluetooth HID接続 → sys-botbase TCP/IP接続に変更
- MACアドレス入力・BTスキャン機能を削除
- IPアドレス + ポート番号で接続
- `SysBotBaseController.kt` 新規追加

---
