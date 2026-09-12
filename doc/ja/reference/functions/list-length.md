# list-length

`(list-length list)`

真リストの要素数。循環リストの場合は nil を返します。これが [`length`](length.md) との違いであり、走査が 2 つのカーソル(亀と兎)である理由です。ドットリストやリスト以外の引数は `type-error` をシグナルします。

```lisp
(list-length '(a b c)) ; => 3
```

```lisp
(list-length nil) ; => 0
```
