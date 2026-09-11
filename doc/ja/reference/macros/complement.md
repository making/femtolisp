# complement

`(complement function)`

`function` と逆の答えを返す述語を返します。`function` が `nil` を返すところで `t` を返し、その逆も同様です。返される述語は 3 引数までの呼び出しを受け付けるため、1 引数の述語だけでなく等価性の指定子 (`:test` / `:test-not`) としても使えます。簡易版: Common Lisp の `complement` は引数の個数に上限がありませんが、こちらは 3 引数までです。また `complement` はインライン展開されるため `#'complement` は使えません。

```lisp
(funcall (complement #'evenp) 3) ; => T
```

```lisp
(remove-if (complement #'oddp) '(1 2 3 4 5)) ; => (1 3 5)
```

```lisp
(remove 3 (list 1 2 3 4) :test-not (complement #'eql)) ; => (1 2 4)
```
