# count-if

`(count-if predicate sequence &key key start end from-end)`

`sequence` の要素のうち `predicate` を満たすものの個数を返します。シーケンスにはリストまたは文字列 (要素は文字) を渡せます。`count` の述語ベース版です。`:start`/`:end` は走査する部分列を区切り、`:from-end` は要素を訪れる順序を逆にします。個数自体は変わりませんが、副作用のある `:key` や `:test` は逆順で呼ばれます。

```lisp
(count-if #'evenp '(1 2 3 4)) ; => 2
```

```lisp
(count-if #'digit-char-p "a1b2") ; => 2
```

```lisp
(count-if #'evenp '(1 2 3 4) :start 2) ; => 1
```
