# call-flow-openrefine

## goal

`slice-call-flow.tsv` を OpenRefine に取り込み、
call-flow の平坦表を GUI 上で観察・絞り込みできる状態にする。

## input

- `../call-flow-slice/exports/foo-call-flow.tsv`

## expected usage

- `depth` facet で層ごとの広がりを見る
- `parent-method` で直接の呼出元を追う
- `method-file` / `method-start-line` で定義位置を確認する
- `call-file` / `call-line` で呼び出しサイトを確認する

## run

```bash
bin/run-trial trials/samples/call-flow-openrefine/trial.edn
```

## note

この trial は、先に `trials/samples/call-flow-slice/trial.edn` を実行して
`foo-call-flow.tsv` が生成済みであることを前提とする。
