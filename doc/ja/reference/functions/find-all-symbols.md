# find-all-symbols

`(find-all-symbols symbol &optional package)`

`symbol` と名前が `string=` で等しい、重複のない全シンボルのうち
`package` から到達可能なもの -- 省略時は全登録パッケージが対象である。各結果はコードが綴る形
(`cl` メンバーは裸で読める)であるため、`member`
による検査が機能する。キーワードが列挙されることはない: intern
テーブルを持たないため、歩けるキーワード宇宙が存在しない。

```lisp
(find-all-symbols 'car) ; => (CAR)
(find-all-symbols 'car :cl-user) ; => (CAR)
```
