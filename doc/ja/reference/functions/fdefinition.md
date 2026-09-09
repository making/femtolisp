# fdefinition

`(fdefinition symbol)`

シンボルの関数値を返します。[`symbol-function`](symbol-function.md) と同じです (setf 関数名はサポートされません)。

クォートされたシンボルリテラル (`(fdefinition 'car)`) はコンパイラではコンパイル時に解決されます。実行時に計算されたシンボルはコンパイル済み名前レジストリを通じて遅延解決され、同じ関数値を返します — [`symbol-function`](symbol-function.md) と同じで、相違点はありません。

```lisp
(funcall (fdefinition 'car) '(1 2 3)) ; => 1
```

`fdefinition` は [`symbol-function`](symbol-function.md) と同じ `setf` の place です: `(setf (fdefinition 'name) fn)` は `fn` をそのシンボルのグローバルな関数定義としてインストールします。
