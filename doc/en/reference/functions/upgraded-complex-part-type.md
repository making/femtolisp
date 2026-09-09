# upgraded-complex-part-type

`(upgraded-complex-part-type type)`

Returns the upgraded part type for a complex type specifier: a designator naming a subtype of `real` answers itself (`'single-float` stays `'single-float`), while a compound real specifier answers its head's name (`'(integer 0 10)` answers `integer`). Anything else signals an error. Works on all four backends.

```lisp
(upgraded-complex-part-type 'integer) ; => INTEGER
```

```lisp
(upgraded-complex-part-type '(integer 0 10)) ; => INTEGER
```
