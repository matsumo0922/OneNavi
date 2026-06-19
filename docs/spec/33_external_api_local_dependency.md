# 33. 外部API ライブラリのローカル依存化

## 目的

外部API ライブラリを OneNavi の git 管理下から外し、通常の IDE import では外部
ソースを読み込まない構成にする。これにより、git worktree 利用時の submodule 管理負荷と
IDE 補完対象の肥大化を避ける。

## 通常利用

外部API ライブラリは、ローカル file Maven repository に publish した AAR として
解決する。既定の repository path は以下。

```text
~/.gradle/local-repos/ext-api
```

任意の場所を使う場合は `extApiRepositoryPath` または `EXT_API_REPOSITORY_PATH` を
指定する。

初回セットアップは Makefile target から実行できる。既定では OneNavi と同じ親
ディレクトリにある checkout を使い、checkout が存在しない場合は `EXT_API_GIT_URL` を
指定すると clone してから publish する。

```bash
EXT_API_GIT_URL=<private-repository-url> make ext-api-setup
make ext-api-setup
```

```bash
scripts/publish_ext_api_to_local_repo.sh /path/to/external-api-library
./gradlew :composeApp:assembleDebug
```

依存 version は既定でローカル repository の Maven metadata に含まれる `latest.release`
を使う。固定したい場合は `extApiVersion` または `EXT_API_VERSION` を指定する。

## ソース編集時

外部API ライブラリ自体を編集しながら OneNavi をビルドしたい場合のみ、明示的に
composite build を有効化する。

```bash
./gradlew :composeApp:assembleDebug -PextApiPath=/path/to/external-api-library
```

この指定を IDE import の既定にしないことで、通常時は外部ライブラリのソースと調査用
ファイルが OneNavi 側の補完対象に入らない。
