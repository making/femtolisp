;;;; uiop/stream -- file contents and the temporary-file directory.
;;;; Canonical shape; see .kb/uiop.md.

;; Chunked, NOT (make-string (file-length s)): file-length answers nil on both
;; WASM backends (no WASI filestat call is imported, .kb/read-load-streams.md), so
;; sizing the buffer from it traps there. The loop also reads EOF at most once --
;; it stops as soon as a read comes back short -- because a SECOND read past EOF
;; traps on the --component backend (the adapter's stream_read after the writable
;; end dropped).
;;
;; The chunks accumulate into a string OUTPUT STREAM, never into a string: every
;; backend's string stream appends into a buffer that doubles, while
;; (setq acc (concatenate 'string acc chunk)) re-copies everything read so far on
;; every chunk and is quadratic in the file (.kb/string-accumulate-cost.md).
;; with-output-to-string costs no extra machinery here -- with-open-file already
;; puts the wasm module in EH mode.
(defun uiop/stream:read-file-string (%rfs-file &rest %rfs-keys)
  (with-open-file (%rfs-in %rfs-file)
    (with-output-to-string (%rfs-out)
      (let ((%rfs-buf (make-string 4096)) (%rfs-n 4096))
        (while (= %rfs-n 4096)
          (setq %rfs-n (read-sequence %rfs-buf %rfs-in))
          (when (> %rfs-n 0) (write-string %rfs-buf %rfs-out :end %rfs-n)))))))

;; $TMPDIR or /tmp/: getenv is the one environment reader every backend has, and
;; a backend whose environment is empty (both WASM ones without --env) takes the
;; fallback rather than failing.
(defun uiop/stream:default-temporary-directory ()
  (let ((%dtd-e (uiop/os:getenv "TMPDIR")))
    (uiop/pathname:ensure-directory-pathname
     (if (and %dtd-e (string/= %dtd-e "")) %dtd-e "/tmp"))))
