# 33. 外部ナビ API ライブラリのローカル依存化

## 目的

外部ナビ API ライブラリを OneNavi の git 管理下から外し、通常の IDE import では外部
ソースを読み込まない構成にする。これにより、git worktree 利用時の submodule 管理負荷と
IDE 補完対象の肥大化を避ける。

## 通常利用

外部ナビ API ライブラリは、ローカル file Maven repository に publish した AAR として
解決する。既定の repository path は以下。

```text
~/.gradle/local-repos/ext-nav-api
```

任意の場所を使う場合は `extNavApiRepositoryPath` または `EXT_NAV_API_REPOSITORY_PATH` を
指定する。

```bash
scripts/publish_ext_nav_api_to_local_repo.sh /path/to/external-nav-api-library
./gradlew :composeApp:assembleDebug
```

依存 version は既定で `0.0.1`。変更する場合は `extNavApiVersion` または
`EXT_NAV_API_VERSION` を指定する。

## ソース編集時

外部ナビ API ライブラリ自体を編集しながら OneNavi をビルドしたい場合のみ、明示的に
composite build を有効化する。

```bash
./gradlew :composeApp:assembleDebug -PextNavApiPath=/path/to/external-nav-api-library
```

この指定を IDE import の既定にしないことで、通常時は外部ライブラリのソースと調査用
ファイルが OneNavi 側の補完対象に入らない。
